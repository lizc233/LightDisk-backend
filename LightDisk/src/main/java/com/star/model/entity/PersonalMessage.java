package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 个人消息表（点对点）
 * message_type: 1-系统自动 2-管理员私信 3-分享通知
 */
@Data
@TableName("personal_message")
public class PersonalMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发送者ID（系统消息为NULL） */
    private Long senderId;

    /** 接收者ID */
    private Long receiverId;

    /** 消息标题 */
    private String title;

    /** 消息内容 */
    private String content;

    /** 类型：1-系统自动 2-管理员私信 3-分享通知 */
    private Integer messageType;

    /** 关联ID（如分享ID、文件ID） */
    private Long relatedId;

    /** 是否已读：0-未读 1-已读 */
    private Integer isRead;

    /** 阅读时间 */
    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
