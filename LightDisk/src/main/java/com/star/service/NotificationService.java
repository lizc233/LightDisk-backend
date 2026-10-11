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
     * 失效指定用户的未读数缓存（任何会产生未读/已读变更的写入方调用，
     * 下次查询重新从数据库计算，保证与数据库强同步）
     */
    void evictUnreadCache(Long userId);

    /**
     * 失效所有用户的未读数缓存（管理员发布/撤回/删除公告等全局变更时调用）
     */
    void evictAllUnreadCache();
}
