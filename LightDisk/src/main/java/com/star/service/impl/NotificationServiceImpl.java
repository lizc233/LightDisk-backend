package com.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.mapper.AnnouncementMapper;
import com.star.mapper.AnnouncementReadMapper;
import com.star.mapper.PersonalMessageMapper;
import com.star.mapper.UserMapper;
import com.star.model.entity.Announcement;
import com.star.model.entity.AnnouncementRead;
import com.star.model.entity.PersonalMessage;
import com.star.model.vo.NotificationItemVO;
import com.star.service.NotificationService;
import com.star.service.UserContactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 通知中心 Service
 *
 * 未读数策略（v3）：数据库是唯一事实源，Redis 只做「读穿透缓存」：
 *   1. 查询：Redis 命中直接返回；未命中→查 DB→回写缓存（短 TTL 兜底）
 *   2. 任何写入（发私信/分享通知/标记已读/全部已读）→ 立即 evict 对应用户缓存
 *   3. 管理员发布/撤回/删除公告等全局变更 → evict 全部用户缓存
 * 不再做 ±1 增量维护（多入口下易失步），因此缓存值永远与数据库一致。
 * 未读数 = 系统通知未读（LEFT JOIN 已读表，一条 SQL）+ 个人私信未读
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final AnnouncementMapper announcementMapper;
    private final AnnouncementReadMapper announcementReadMapper;
    private final PersonalMessageMapper personalMessageMapper;
    private final UserMapper userMapper;
    private final UserContactService userContactService;
    private final org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    private static final String REDIS_KEY_UNREAD = "notification:unread:";
    private static final long CACHE_EXPIRE_SECONDS = 30; // 短 TTL 兜底，即使漏 evict 也最多滞后 30s

    @Override
    public Map<String, Object> getUnreadCount(Long userId) {
        String redisKey = REDIS_KEY_UNREAD + userId;

        // 1. Redis 读穿透缓存
        String cached = stringRedisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            String[] parts = cached.split(":");
            return buildUnreadMap(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        }

        // 2. miss → 查数据库（事实源）→ 回写短缓存
        Map<String, Object> result = calculateUnreadFromDB(userId);
        long aUnread = (Long) result.get("announcementUnread");
        long mUnread = (Long) result.get("messageUnread");
        stringRedisTemplate.opsForValue().set(redisKey,
                aUnread + ":" + mUnread,
                CACHE_EXPIRE_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        return result;
    }

    @Override
    public void evictUnreadCache(Long userId) {
        if (userId != null) {
            stringRedisTemplate.delete(REDIS_KEY_UNREAD + userId);
        }
    }

    @Override
    public void evictAllUnreadCache() {
        // 用户量级小（课设），SCAN 清理足够；将来量大可改为版本号 key
        try (org.springframework.data.redis.core.Cursor<String> cursor = stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(REDIS_KEY_UNREAD + "*").build())) {
            while (cursor.hasNext()) {
                stringRedisTemplate.delete(cursor.next());
            }
        } catch (Exception e) {
            log.warn("清理未读缓存失败，等待短 TTL 自然过期", e);
        }
    }

    @Override
    public Map<String, Object> getNotificationList(Long userId, Integer pageNum, Integer pageSize) {
        // 获取系统通知（含已读状态）
        List<Announcement> notifications = announcementMapper.selectSystemNotificationsForUser(userId);
        Set<Long> readAnnIds = announcementReadMapper.selectList(
                new LambdaQueryWrapper<AnnouncementRead>().eq(AnnouncementRead::getUserId, userId)
        ).stream().map(AnnouncementRead::getAnnouncementId).collect(Collectors.toSet());

        // 我的联系人ID集合，用于标记陌生消息
        Set<Long> contactIds = userContactService.contactIdSet(userId);

        // 获取个人消息
        Page<PersonalMessage> msgPage = new Page<>(pageNum, pageSize / 2);
        LambdaQueryWrapper<PersonalMessage> msgWrapper = new LambdaQueryWrapper<>();
        msgWrapper.eq(PersonalMessage::getReceiverId, userId)
                .orderByDesc(PersonalMessage::getCreatedAt);
        Page<PersonalMessage> msgResult = personalMessageMapper.selectPage(msgPage, msgWrapper);

        // 合并并按时间排序
        List<NotificationItemVO> merged = new ArrayList<>();

        for (Announcement a : notifications) {
            NotificationItemVO vo = new NotificationItemVO();
            vo.setItemType("announcement");
            vo.setAnnouncementId(a.getId());
            vo.setTitle(a.getTitle());
            vo.setContent(a.getContent());
            vo.setType(a.getType());
            vo.setCreatedAt(a.getCreatedAt());
            vo.setIsRead(readAnnIds.contains(a.getId()));
            merged.add(vo);
        }

        for (PersonalMessage m : msgResult.getRecords()) {
            NotificationItemVO vo = new NotificationItemVO();
            vo.setItemType("message");
            vo.setMessageId(m.getId());
            vo.setSenderId(m.getSenderId());
            vo.setTitle(m.getTitle());
            vo.setContent(m.getContent());
            vo.setMessageType(m.getMessageType());
            vo.setRelatedId(m.getRelatedId());
            vo.setAttachments(m.getAttachments());
            vo.setSenderName(getSenderName(m.getSenderId()));
            vo.setSenderIsContact(m.getSenderId() != null && contactIds.contains(m.getSenderId()));
            vo.setCreatedAt(m.getCreatedAt());
            vo.setIsRead(m.getIsRead() == 1);
            merged.add(vo);
        }

        // 按时间倒序
        merged.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        // 分页
        int total = merged.size();
        int fromIndex = (pageNum - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<NotificationItemVO> paged = fromIndex < total
                ? merged.subList(fromIndex, toIndex)
                : Collections.emptyList();

        Map<String, Object> data = new HashMap<>();
        data.put("list", paged);
        data.put("total", (long) total);
        data.put("pageNum", pageNum);
        data.put("pageSize", pageSize);
        return data;
    }

    @Override
    @Transactional
    public void markAnnouncementRead(Long userId, Long announcementId) {
        // 幂等：检查是否已读
        Long count = announcementReadMapper.selectCount(
                new LambdaQueryWrapper<AnnouncementRead>()
                        .eq(AnnouncementRead::getAnnouncementId, announcementId)
                        .eq(AnnouncementRead::getUserId, userId)
        );
        if (count > 0) return; // 已读过，跳过

        AnnouncementRead readRecord = new AnnouncementRead();
        readRecord.setAnnouncementId(announcementId);
        readRecord.setUserId(userId);
        readRecord.setReadAt(LocalDateTime.now());
        announcementReadMapper.insert(readRecord);

        evictUnreadCache(userId);
    }

    @Override
    @Transactional
    public void markMessageRead(Long userId, Long messageId) {
        PersonalMessage msg = personalMessageMapper.selectById(messageId);
        if (msg == null || !msg.getReceiverId().equals(userId)) {
            return;
        }
        if (msg.getIsRead() != null && msg.getIsRead() == 1) return; // 已读

        msg.setIsRead(1);
        msg.setReadAt(LocalDateTime.now());
        personalMessageMapper.updateById(msg);

        evictUnreadCache(userId);
    }

    @Override
    @Transactional
    public void markAllRead(Long userId) {
        // 1. 标记所有未读系统通知为已读
        List<Announcement> allNotifications = announcementMapper.selectSystemNotificationsForUser(userId);
        Set<Long> existingReadIds = announcementReadMapper.selectList(
                new LambdaQueryWrapper<AnnouncementRead>().eq(AnnouncementRead::getUserId, userId)
        ).stream().map(AnnouncementRead::getAnnouncementId).collect(Collectors.toSet());

        for (Announcement a : allNotifications) {
            if (!existingReadIds.contains(a.getId())) {
                AnnouncementRead readRecord = new AnnouncementRead();
                readRecord.setAnnouncementId(a.getId());
                readRecord.setUserId(userId);
                readRecord.setReadAt(LocalDateTime.now());
                announcementReadMapper.insert(readRecord);
            }
        }

        // 2. 标记所有个人消息为已读
        PersonalMessage updateMsg = new PersonalMessage();
        updateMsg.setIsRead(1);
        updateMsg.setReadAt(LocalDateTime.now());
        personalMessageMapper.update(updateMsg,
                new LambdaQueryWrapper<PersonalMessage>()
                        .eq(PersonalMessage::getReceiverId, userId)
                        .eq(PersonalMessage::getIsRead, 0)
        );

        evictUnreadCache(userId);
    }

    // ==================== 私有方法 ====================

    /**
     * 直接查库计算未读数：系统通知（LEFT JOIN 已读表）+ 个人私信
     */
    private Map<String, Object> calculateUnreadFromDB(Long userId) {
        Long announcementUnread = announcementMapper.countUnreadSystemNotifications(userId);

        Long messageUnread = personalMessageMapper.selectCount(
                new LambdaQueryWrapper<PersonalMessage>()
                        .eq(PersonalMessage::getReceiverId, userId)
                        .eq(PersonalMessage::getIsRead, 0)
        );

        return buildUnreadMap(announcementUnread == null ? 0 : announcementUnread,
                messageUnread == null ? 0 : messageUnread);
    }

    private Map<String, Object> buildUnreadMap(long aUnread, long mUnread) {
        Map<String, Object> map = new HashMap<>();
        map.put("announcementUnread", aUnread);
        map.put("messageUnread", mUnread);
        map.put("totalUnread", aUnread + mUnread);
        return map;
    }

    private String getSenderName(Long senderId) {
        if (senderId == null) return "系统";
        try {
            com.star.model.entity.User user = userMapper.selectById(senderId);
            return user != null ? user.getUsername() : "未知用户";
        } catch (Exception e) {
            return "用户" + senderId;
        }
    }
}
