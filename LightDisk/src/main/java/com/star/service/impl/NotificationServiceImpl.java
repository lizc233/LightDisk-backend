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
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 通知中心 Service
 *
 * <h3>未读数策略（v4：Redis 计数 + 延迟落库 + 对账兜底）</h3>
 * 思路：Redis 承担「高频读 + 即时反馈」，数据库承担「权威事实」，二者通过「延迟落库 + 对账」保持一致。
 * <pre>
 *   ┌─ 读未读数 ─────────────────────────────────────────────┐
 *   │ 直接读 Redis Hash(ld:notif:unread:{uid}) → 命中即返回    │
 *   │ 未命中 → 查 DB（事实源）→ 回填 Redis                     │
 *   │ Redis 挂了 → 直接查 DB（降级，系统仍可用）                 │
 *   └───────────────────────────────────────────────────────┘
 *   ┌─ 标记已读（高频写）──────────────────────────────────────┐
 *   │ 1) Redis 计数立即 -1  → 用户秒级反馈（不阻塞、不打 MySQL） │
 *   │ 2) 操作压入待落库队列 ld:notif:pending（不立即写 DB）      │
 *   │ 3) 后台定时每 2s 批量把队列刷进 DB（操作「慢慢落库」）      │
 *   │ Redis 挂了 → 退化为同步直写 DB                            │
 *   └───────────────────────────────────────────────────────┘
 *   ┌─ 强同步（防长期失步）───────────────────────────────────┐
 *   │ 用户登录时：先刷队列，再按 DB 重算并覆盖 Redis            │
 *   │ 定时（默认 3min）：全量活跃用户对账，DB 为准             │
 *   └───────────────────────────────────────────────────────┘
 *   Redis 崩溃兜底：计数与待落库队列都在 Redis 中，Redis 挂掉后
 *   队列丢失 → 那些「已看但还没写进 DB」的消息在 DB 里仍是未读，
 *   下次登录对账时会按 DB 恢复为未读，即「以数据库为准」。
 * </pre>
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
    private final StringRedisTemplate redis;

    /** 未读计数 Hash：{a:系统通知未读, m:私信未读} —— Redis 为主读入口 */
    private static final String KEY_UNREAD = "ld:notif:unread:";
    /** 延迟落库队列（LIST，元素形如 "OP|uid|id"） */
    private static final String KEY_PENDING = "ld:notif:pending";
    /** 已读去重集合（防止同一消息在一次刷盘窗口内被重复 -1） */
    private static final String KEY_GUARD_MSG = "ld:notif:guard:msg:";
    private static final String KEY_GUARD_ANN = "ld:notif:guard:ann:";

    private static final String FIELD_A = "a";
    private static final String FIELD_M = "m";

    /** 缓存 TTL：仅作「长时间无人访问」的自然淘汰；活跃用户由定时对账不断续期 */
    private static final long UNREAD_TTL_SECONDS = 24 * 3600L;
    private static final long GUARD_TTL_SECONDS = 24 * 3600L;

    private static final String OP_MSG_READ = "MR";
    private static final String OP_ANN_READ = "AR";
    private static final String OP_ALL_READ = "MRA";
    private static final String SEP = "|";

    /** 单次刷盘最多处理的操作数 */
    private static final int FLUSH_BATCH = 500;

    // ==================== 未读数读取 ====================

    @Override
    public Map<String, Object> getUnreadCount(Long userId) {
        if (userId == null) {
            return buildUnreadMap(0, 0);
        }
        String key = KEY_UNREAD + userId;

        // 1) Redis 优先：命中直接返回，不打数据库
        Map<Object, Object> cached;
        try {
            cached = redis.opsForHash().entries(key);
        } catch (Exception e) {
            log.warn("[通知] Redis 不可用，未读数降级查库 uid={}", userId, e);
            return calculateUnreadFromDB(userId); // 2) 降级：数据库兜底
        }
        if (cached != null && !cached.isEmpty()) {
            return buildUnreadMap(parseLong(cached.get(FIELD_A)), parseLong(cached.get(FIELD_M)));
        }

        // 3) 缓存未命中 → 查数据库（事实源）→ 回填
        Map<String, Object> db = calculateUnreadFromDB(userId);
        cacheUnread(userId, key, (Long) db.get("announcementUnread"), (Long) db.get("messageUnread"));
        return db;
    }

    // ==================== 标记已读（Redis 即时反馈 + 延迟落库）====================

    @Override
    public void markMessageRead(Long userId, Long messageId) {
        if (userId == null || messageId == null) {
            return;
        }
        // 轻量归属校验（主键查询），顺便拿到 DB 中的已读状态
        PersonalMessage msg = personalMessageMapper.selectById(messageId);
        if (msg == null || !userId.equals(msg.getReceiverId())) {
            return;
        }
        boolean alreadyReadInDb = msg.getIsRead() != null && msg.getIsRead() == 1;

        // 尝试走 Redis 快路径（即时反馈 + 延迟落库）
        Boolean handled = redisMarkMessageRead(userId, messageId, alreadyReadInDb);
        if (Boolean.TRUE.equals(handled)) {
            return;
        }
        // Redis 不可用 → 同步直写数据库兜底
        doPersistMessageRead(userId, messageId);
    }

    @Override
    public void markAnnouncementRead(Long userId, Long announcementId) {
        if (userId == null || announcementId == null) {
            return;
        }
        Boolean handled = redisMarkAnnouncementRead(userId, announcementId);
        if (Boolean.TRUE.equals(handled)) {
            return;
        }
        doPersistAnnouncementRead(userId, announcementId);
    }

    @Override
    public void markAllRead(Long userId) {
        if (userId == null) {
            return;
        }
        Boolean handled = redisMarkAllRead(userId);
        if (Boolean.TRUE.equals(handled)) {
            return;
        }
        doPersistAllRead(userId);
    }

    // ==================== 新未读的产生（+1）====================

    @Override
    public void onNewMessage(Long receiverId) {
        if (receiverId == null) {
            return;
        }
        bumpUnread(receiverId, FIELD_M, 1L);
    }

    /**
     * 缓存命中时对某个计数原子 +delta；未命中则不做处理
     * （下次读取会自然从数据库取到最新值，避免「先加后盖」的重复计数）。
     */
    private void bumpUnread(Long userId, String field, long delta) {
        String key = KEY_UNREAD + userId;
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                return; // 无缓存：留给下次读时按 DB 物化
            }
            redis.opsForHash().increment(key, field, delta);
            redis.expire(key, UNREAD_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[通知] 未读 +{} 失败（将由读时按 DB 重算兜底）uid={}", delta, userId, e);
        }
    }

    // ==================== 对账（以数据库为准）====================

    @Override
    public void reconcileUnread(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            // 先把待落库操作尽量刷进 DB，保证随后回填的是最新事实
            flushPendingWrites();
        } catch (Exception e) {
            log.warn("[通知] 登录对账前刷盘失败 uid={}", userId, e);
        }
        // 若仍有在途（未落库）的已读操作，本轮跳过覆盖，
        // 否则会把「已看但还没写进 DB」的消息按 DB 又恢复成未读（等刷盘完再对账）
        long pending = pendingSize();
        if (pending > 0) {
            log.info("[通知] 仍有 {} 条待落库操作，跳过本次登录对账 uid={}", pending, userId);
            return;
        }
        try {
            Map<String, Object> db = calculateUnreadFromDB(userId);
            cacheUnread(userId, KEY_UNREAD + userId,
                    (Long) db.get("announcementUnread"), (Long) db.get("messageUnread"));
        } catch (Exception e) {
            log.warn("[通知] 登录对账失败（不影响登录）uid={}", userId, e);
        }
    }

    /**
     * 定时把「延迟落库队列」批量刷进数据库（默认每 2s）。
     * 失败的操作直接丢弃：数据库仍是权威，未落库的已读会在下次对账时恢复为未读。
     */
    @Scheduled(fixedDelayString = "${lightdisk.notification.flush-interval:2000}")
    public void flushPendingWrites() {
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(KEY_PENDING))) {
                return;
            }
            for (int i = 0; i < FLUSH_BATCH; i++) {
                String op = redis.opsForList().rightPop(KEY_PENDING);
                if (op == null) {
                    break;
                }
                try {
                    applyPendingOp(op);
                } catch (Exception ex) {
                    log.warn("[通知] 延迟落库单条失败（将由数据库对账兜底），op={}", op, ex);
                }
            }
        } catch (Exception e) {
            log.warn("[通知] 延迟落库队列不可用（等待恢复）", e);
        }
    }

    /**
     * 定时全量对账：把每个活跃用户的未读数按数据库重算并覆盖 Redis（默认每 3min）。
     * 这是防止「多入口写导致计数长期偏移」的最终保险。
     */
    @Scheduled(fixedDelayString = "${lightdisk.notification.reconcile-interval:180000}",
            initialDelayString = "${lightdisk.notification.reconcile-initial-delay:60000}")
    public void reconcileAllUsers() {
        try {
            flushPendingWrites();
        } catch (Exception e) {
            log.warn("[通知] 定时对账前刷盘失败", e);
        }
        long pending = pendingSize();
        if (pending > 0) {
            log.info("[通知] 仍有 {} 条待落库操作，跳过本次定时对账（下一轮再同步）", pending);
            return;
        }
        Set<Long> userIds;
        try {
            userIds = scanActiveUsers();
        } catch (Exception e) {
            log.warn("[通知] 定时对账扫描用户失败", e);
            return;
        }
        int ok = 0;
        for (Long uid : userIds) {
            try {
                Map<String, Object> db = calculateUnreadFromDB(uid);
                cacheUnread(uid, KEY_UNREAD + uid,
                        (Long) db.get("announcementUnread"), (Long) db.get("messageUnread"));
                ok++;
            } catch (Exception ex) {
                log.warn("[通知] 对账用户 {} 失败", uid, ex);
            }
        }
        if (ok > 0) {
            log.info("[通知] 定时对账完成，同步 {} 个用户（以数据库为准）", ok);
        }
    }

    // ==================== 缓存维护 ====================

    @Override
    public void evictUnreadCache(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            redis.delete(KEY_UNREAD + userId);
        } catch (Exception e) {
            log.warn("[通知] 清理未读缓存失败 uid={}（等待定时对账兜底）", userId, e);
        }
    }

    @Override
    public void evictAllUnreadCache() {
        // 用户量级小（课设），SCAN 清理足够；将来量大可改为版本号 key
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(KEY_UNREAD + "*").count(500).build())) {
            while (cursor.hasNext()) {
                redis.delete(cursor.next());
            }
        } catch (Exception e) {
            log.warn("[通知] 清理全部未读缓存失败（等待定时对账兜底）", e);
        }
    }

    // ==================== 通知列表 ====================

    @Override
    public Map<String, Object> getNotificationList(Long userId, Integer pageNum, Integer pageSize) {
        int num = (pageNum == null || pageNum < 1) ? 1 : pageNum;
        int size = (pageSize == null || pageSize < 1) ? 10 : pageSize;

        // 获取系统通知（含已读状态）
        List<Announcement> notifications = announcementMapper.selectSystemNotificationsForUser(userId);
        Set<Long> readAnnIds = announcementReadMapper.selectList(
                new LambdaQueryWrapper<AnnouncementRead>().eq(AnnouncementRead::getUserId, userId)
        ).stream().map(AnnouncementRead::getAnnouncementId).collect(Collectors.toSet());

        // 叠加「尚未落库」的已读（延迟落库窗口内列表也要显示为已读）
        readAnnIds.addAll(guardMembers(KEY_GUARD_ANN + userId));

        // 我的联系人ID集合，用于标记陌生消息
        Set<Long> contactIds = userContactService.contactIdSet(userId);

        // 获取个人消息
        Page<PersonalMessage> msgPage = new Page<>(num, Math.max(1, size / 2));
        LambdaQueryWrapper<PersonalMessage> msgWrapper = new LambdaQueryWrapper<>();
        msgWrapper.eq(PersonalMessage::getReceiverId, userId)
                .orderByDesc(PersonalMessage::getCreatedAt);
        Page<PersonalMessage> msgResult = personalMessageMapper.selectPage(msgPage, msgWrapper);

        Set<Long> pendingMsgRead = guardMembers(KEY_GUARD_MSG + userId);

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
            vo.setIsRead((m.getIsRead() != null && m.getIsRead() == 1) || pendingMsgRead.contains(m.getId()));
            merged.add(vo);
        }

        // 按时间倒序
        merged.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        // 分页
        int total = merged.size();
        int fromIndex = (num - 1) * size;
        int toIndex = Math.min(fromIndex + size, total);
        List<NotificationItemVO> paged = fromIndex < total
                ? merged.subList(fromIndex, toIndex)
                : Collections.emptyList();

        Map<String, Object> data = new HashMap<>();
        data.put("list", paged);
        data.put("total", (long) total);
        data.put("pageNum", num);
        data.put("pageSize", size);
        return data;
    }

    // ==================== Redis 快路径内部实现 ====================

    /**
     * 尝试在 Redis 侧完成「标记私信已读」。
     *
     * @return TRUE  已在 Redis 侧处理（含重复请求 / 已读），调用方无需再落库；
     *         FALSE Redis 不可用，调用方需同步直写数据库兜底。
     */
    private Boolean redisMarkMessageRead(Long userId, Long messageId, boolean alreadyReadInDb) {
        try {
            Long added = redis.opsForSet().add(KEY_GUARD_MSG + userId, String.valueOf(messageId));
            boolean firstTime = added != null && added > 0;
            if (firstTime) {
                redis.expire(KEY_GUARD_MSG + userId, GUARD_TTL_SECONDS, TimeUnit.SECONDS);
            }
            if (firstTime && !alreadyReadInDb) {
                decrementUnread(userId, FIELD_M);
                redis.opsForList().leftPush(KEY_PENDING, OP_MSG_READ + SEP + userId + SEP + messageId);
            }
            return Boolean.TRUE;
        } catch (Exception e) {
            log.warn("[通知] Redis 标记已读失败，降级同步落库 uid={} msgId={}", userId, messageId, e);
            return Boolean.FALSE;
        }
    }

    private Boolean redisMarkAnnouncementRead(Long userId, Long announcementId) {
        try {
            Long added = redis.opsForSet().add(KEY_GUARD_ANN + userId, String.valueOf(announcementId));
            boolean firstTime = added != null && added > 0;
            if (firstTime) {
                redis.expire(KEY_GUARD_ANN + userId, GUARD_TTL_SECONDS, TimeUnit.SECONDS);
                if (!isAnnouncementReadInDb(userId, announcementId)) {
                    decrementUnread(userId, FIELD_A);
                    redis.opsForList().leftPush(KEY_PENDING, OP_ANN_READ + SEP + userId + SEP + announcementId);
                }
            }
            return Boolean.TRUE;
        } catch (Exception e) {
            log.warn("[通知] Redis 标记公告已读失败，降级同步落库 uid={} annId={}", userId, announcementId, e);
            return Boolean.FALSE;
        }
    }

    private Boolean redisMarkAllRead(Long userId) {
        try {
            String key = KEY_UNREAD + userId;
            redis.opsForHash().put(key, FIELD_A, "0");
            redis.opsForHash().put(key, FIELD_M, "0");
            redis.expire(key, UNREAD_TTL_SECONDS, TimeUnit.SECONDS);
            redis.opsForList().leftPush(KEY_PENDING, OP_ALL_READ + SEP + userId);
            return Boolean.TRUE;
        } catch (Exception e) {
            log.warn("[通知] Redis 全部已读失败，降级同步落库 uid={}", userId, e);
            return Boolean.FALSE;
        }
    }

    /** 未读计数 -1（若缓存缺失，先按 DB 事实物化，避免丢失这次扣减）；需在 Redis 可用时调用 */
    private void decrementUnread(Long userId, String field) {
        String key = KEY_UNREAD + userId;
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            Map<String, Object> db = calculateUnreadFromDB(userId);
            cacheUnread(userId, key, (Long) db.get("announcementUnread"), (Long) db.get("messageUnread"));
        }
        Long v = redis.opsForHash().increment(key, field, -1L);
        if (v != null && v < 0) {
            redis.opsForHash().put(key, field, "0");
        }
        redis.expire(key, UNREAD_TTL_SECONDS, TimeUnit.SECONDS);
    }

    // ==================== 延迟落库执行 ====================

    private void applyPendingOp(String op) {
        if (op == null || op.isEmpty()) {
            return;
        }
        String[] p = op.split("\\" + SEP);
        if (p.length < 2) {
            return;
        }
        if (OP_ALL_READ.equals(p[0])) {
            doPersistAllRead(Long.parseLong(p[1]));
        } else if (OP_MSG_READ.equals(p[0]) && p.length >= 3) {
            doPersistMessageRead(Long.parseLong(p[1]), Long.parseLong(p[2]));
        } else if (OP_ANN_READ.equals(p[0]) && p.length >= 3) {
            doPersistAnnouncementRead(Long.parseLong(p[1]), Long.parseLong(p[2]));
        }
    }

    /** 把「私信已读」落库（幂等：仅当当前仍未读时更新） */
    private void doPersistMessageRead(Long userId, Long messageId) {
        PersonalMessage update = new PersonalMessage();
        update.setIsRead(1);
        update.setReadAt(LocalDateTime.now());
        personalMessageMapper.update(update, new LambdaQueryWrapper<PersonalMessage>()
                .eq(PersonalMessage::getId, messageId)
                .eq(PersonalMessage::getReceiverId, userId)
                .eq(PersonalMessage::getIsRead, 0));
    }

    /** 把「系统通知已读」落库（幂等：已存在则跳过） */
    private void doPersistAnnouncementRead(Long userId, Long announcementId) {
        if (isAnnouncementReadInDb(userId, announcementId)) {
            return;
        }
        AnnouncementRead readRecord = new AnnouncementRead();
        readRecord.setAnnouncementId(announcementId);
        readRecord.setUserId(userId);
        readRecord.setReadAt(LocalDateTime.now());
        announcementReadMapper.insert(readRecord);
    }

    /** 一键全部已读落库 */
    private void doPersistAllRead(Long userId) {
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
                        .eq(PersonalMessage::getIsRead, 0));
    }

    // ==================== 私有工具方法 ====================

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

    private void cacheUnread(Long userId, String key, long aUnread, long mUnread) {
        try {
            HashOperations<String, Object, Object> hash = redis.opsForHash();
            hash.put(key, FIELD_A, String.valueOf(aUnread));
            hash.put(key, FIELD_M, String.valueOf(mUnread));
            redis.expire(key, UNREAD_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[通知] 回填未读缓存失败 uid={}", userId, e);
        }
    }

    private boolean isAnnouncementReadInDb(Long userId, Long announcementId) {
        Long count = announcementReadMapper.selectCount(new LambdaQueryWrapper<AnnouncementRead>()
                .eq(AnnouncementRead::getUserId, userId)
                .eq(AnnouncementRead::getAnnouncementId, announcementId));
        return count != null && count > 0;
    }

    /** 待落库队列长度（Redis 不可用时返回 0） */
    private long pendingSize() {
        try {
            Long n = redis.opsForList().size(KEY_PENDING);
            return n == null ? 0L : n;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 读取「延迟落库窗口内」的已读 ID 集合（Redis 不可用时返回空集，不影响主流程） */
    private Set<Long> guardMembers(String key) {
        Set<Long> ids = new HashSet<>();
        try {
            Set<String> members = redis.opsForSet().members(key);
            if (members != null) {
                for (String s : members) {
                    try {
                        ids.add(Long.parseLong(s));
                    } catch (NumberFormatException ignore) {
                        // 忽略非法成员
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[通知] 读取待落库已读集合失败 key={}", key, e);
        }
        return ids;
    }

    private Set<Long> scanActiveUsers() {        Set<Long> ids = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(KEY_UNREAD + "*").count(500).build())) {
            while (cursor.hasNext()) {
                String k = cursor.next();
                try {
                    ids.add(Long.parseLong(k.substring(KEY_UNREAD.length())));
                } catch (NumberFormatException ignore) {
                    // 非法 key，跳过
                }
            }
        }
        return ids;
    }

    private Map<String, Object> buildUnreadMap(long aUnread, long mUnread) {
        Map<String, Object> map = new HashMap<>();
        map.put("announcementUnread", aUnread);
        map.put("messageUnread", mUnread);
        map.put("totalUnread", aUnread + mUnread);
        return map;
    }

    private long parseLong(Object o) {
        if (o == null) {
            return 0L;
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private String getSenderName(Long senderId) {
        if (senderId == null) {
            return "系统";
        }
        try {
            com.star.model.entity.User user = userMapper.selectById(senderId);
            return user != null ? user.getUsername() : "未知用户";
        } catch (Exception e) {
            return "用户" + senderId;
        }
    }
}
