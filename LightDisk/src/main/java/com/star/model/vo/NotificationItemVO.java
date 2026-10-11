package com.star.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

/**
 * 通知列表合并 VO（铃铛下拉面板用）
 * 合并了系统通知 + 个人消息，按时间倒序
 */
@Data
public class NotificationItemVO {
    /** 类型标识：announcement / message */
    private String itemType;

    // ===== 公告/系统通知字段 =====
    private Long announcementId;
    private String title;
    private String content;
    /** 1-公告 2-系统通知 */
    private Integer type;
    private LocalDateTime createdAt;

    // ===== 个人消息字段 =====
    private Long messageId;
    private Long senderId;
    private String senderName;
    /** 1-系统自动 2-管理员私信 3-分享通知 4-用户私信 */
    private Integer messageType;
    private Long relatedId;
    /** 发送者是否为当前用户的联系人（用于区分陌生消息） */
    private Boolean senderIsContact;

    // ===== 通用 =====
    /** 是否已读 */
    private Boolean isRead = false;
}

/**
 * 未读统计 VO
 */
@Data
class UnreadCountVO {
    /** 系统通知未读数 */
    private Long announcementUnread;
    /** 个人消息未读数 */
    private Long messageUnread;
    /** 总未读数 */
    private Long totalUnread;
}
