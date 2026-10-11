package com.star.model.dto;

import lombok.Data;

/**
 * 管理员发送私信 DTO
 */
@Data
public class PersonalMessageSendDTO {
    /** 接收者ID（必填，支持逗号分隔多个） */
    private String receiverIds;

    /** 消息标题 */
    private String title;

    /** 消息内容 */
    private String content;
}
