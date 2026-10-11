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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 通知中心 Service
 * Redis 缓存未读计数 + SQL 持久化双写策略
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final AnnouncementMapper announcementMapper;
    private final AnnouncementReadMapper announcementReadMapper;
    private final PersonalMessageMapper personalMessageMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserContactService userContactService;

    private static final String REDIS_KEY_UNREAD = "notification:unread:";
    private static final long CACHE_EXPIRE_HOURS = 24; // 缓存 24 小时

    @Override
    public Map<String, Object> getUnreadCount(Long userId) {
        String redisKey = REDIS_KEY_UNREAD + userId;

        // 1. 优先从 Redis 获取
        String cached = stringRedisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            // 格式: "announcementUnread:messageUnread"
            String[] parts = cached.split(":");
            long aUnread = Long.parseLong(parts[0]);
            long mUnread = Long.parseLong(parts[1]);
            return buildUnreadMap(aUnread, mUnread);
        }

        // 2. Redis miss → SQL 兜底 + 回写缓存
        Map<String, Object> result = calculateUnreadFromDB(userId);

        // 写入 Redis
        long aUnread = (Long) result.get("announcementUnread");
        long mUnread = (Long) result.get("messageUnread");
        stringRedisTemplate.opsForValue().set(redisKey,
                aUnread + ":" + mUnread,
                CACHE_EXPIRE_HOURS, TimeUnit.HOURS);

        return result;
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
            vo.setContent(a.getContent()); // 可截断
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

        // Redis 未读数 -1
        decrementUnreadCache(userId, "announcement");
    }

    @Override
    @Transactional
    public void markMessageRead(Long userId, Long messageId) {
        PersonalMessage msg = personalMessageMapper.selectById(messageId);
        if (msg == null || !msg.getReceiverId().equals(userId)) {
            return;
        }
        if (msg.getIsRead() == 1) return; // 已读

        msg.setIsRead(1);
        msg.setReadAt(LocalDateTime.now());
        personalMessageMapper.updateById(msg);

        // Redis 未读数 -1
        decrementUnreadCache(userId, "message");
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

        // 3. 清除 Redis 缓存（下次查询会重新计算）
        stringRedisTemplate.delete(REDIS_KEY_UNREAD + userId);
    }

    // ==================== 私有方法 ====================

    private Map<String, Object> calculateUnreadFromDB(Long userId) {
        // 系统通知未读数：LEFT JOIN ... WHERE read.id IS NULL
        Long announcementUnread = announcementMapper.selectSystemNotificationsForUser(userId).stream()
                .filter(a -> announcementReadMapper.selectCount(
                        new LambdaQueryWrapper<AnnouncementRead>()
                                .eq(AnnouncementRead::getAnnouncementId, a.getId())
                                .eq(AnnouncementRead::getUserId, userId)
                ) == 0).count();

        // 个人消息未读数
        Long messageUnread = personalMessageMapper.selectCount(
                new LambdaQueryWrapper<PersonalMessage>()
                        .eq(PersonalMessage::getReceiverId, userId)
                        .eq(PersonalMessage::getIsRead, 0)
        );

        return buildUnreadMap(announcementUnread, messageUnread);
    }

    private Map<String, Object> buildUnreadMap(long aUnread, long mUnread) {
        Map<String, Object> map = new HashMap<>();
        map.put("announcementUnread", aUnread);
        map.put("messageUnread", mUnread);
        map.put("totalUnread", aUnread + mUnread);
        return map;
    }

    /**
     * Redis 未读数 -1（原子操作）
     */
    private void decrementUnreadCache(Long userId, String type) {
        String key = REDIS_KEY_UNREAD + userId;
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached == null) return; // 无缓存，不处理

        String[] parts = cached.split(":");
        long aUnread = Long.parseLong(parts[0]);
        long mUnread = Long.parseLong(parts[1]);

        if ("announcement".equals(type)) {
            aUnread = Math.max(0, aUnread - 1);
        } else {
            mUnread = Math.max(0, mUnread - 1);
        }

        stringRedisTemplate.opsForValue().set(key,
                aUnread + ":" + mUnread,
                CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 增加未读数（发布新通知时调用）
     */
    public void incrementUnreadCache(Long userId) {
        String key = REDIS_KEY_UNREAD + userId;
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            String[] parts = cached.split(":");
            long aUnread = Long.parseLong(parts[0]) + 1;
            long mUnread = Long.parseLong(parts[1]);
            stringRedisTemplate.opsForValue().set(key,
                    aUnread + ":" + mUnread,
                    CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        }
        // 如果缓存不存在，不需要处理，下次查询时会从 DB 计算
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
