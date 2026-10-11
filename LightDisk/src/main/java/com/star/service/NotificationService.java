package com.star.service;

import java.util.Map;

/**
 * 通知中心 Service（未读数 + 标记已读 + 合并列表）
 */
public interface NotificationService {

    /**
     * 获取当前用户未读统计（Redis 短缓存，数据库为唯一事实源）
     * 返回 { announcementUnread, messageUnread, totalUnread }
     */
    Map<String, Object> getUnreadCount(Long userId);

    /**
     * 获取合并通知列表（系统通知 + 个人消息，按时间倒序）
     */
    Map<String, Object> getNotificationList(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 标记单条系统通知为已读
     */
    void markAnnouncementRead(Long userId, Long announcementId);

    /**
     * 标记单条个人消息为已读
     */
    void markMessageRead(Long userId, Long messageId);

    /**
     * 一键全部已读
     */
    void markAllRead(Long userId);

    /**
     * 新未读产生时（收到新私信/分享通知等）递增接收者的 Redis 未读计数。
     * 只在缓存命中时原子 +1；未命中则留待下次读取按数据库物化，避免重复计数。
     */
    void onNewMessage(Long receiverId);

    /**
     * 以数据库为准，对账并覆盖该用户的 Redis 未读计数（登录时调用）。
     * 会先尽量刷盘待落库操作，再按数据库重算，修复任何长期偏移。
     */
    void reconcileUnread(Long userId);

    /**
     * 失效指定用户的未读数缓存（下次查询重新从数据库计算）
     */
    void evictUnreadCache(Long userId);

    /**
     * 失效所有用户的未读数缓存（管理员发布/撤回/删除公告等全局变更时调用）
     */
    void evictAllUnreadCache();
}
