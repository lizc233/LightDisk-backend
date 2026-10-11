package com.star.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人消息 VO
 */
@Data
public class PersonalMessageVO {
    private Long id;
    /** 发送者用户名（系统消息为"系统"） */
    private String senderName;
    /** 发送者ID */
    private Long senderId;
    private String title;
    private String content;
    /** 类型：1-系统自动 2-管理员私信 3-分享通知 */
    private Integer messageType;
    /** 关联ID */
    private Long relatedId;
    /** 是否已读 */
    private Integer isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
