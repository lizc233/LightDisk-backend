package com.star.model.dto;

import lombok.Data;

/**
 * 发送私信 DTO
 * 管理员与普通用户共用；message_type 由服务端按调用者角色决定，不接受前端传入
 */
@Data
public class PersonalMessageSendDTO {
    /** 接收者ID（必填，支持逗号分隔多个） */
    private String receiverIds;

    /** 消息标题 */
    private String title;

    /** 消息内容 */
    private String content;

    /** 发送成功后是否把接收者自动加为联系人（仅普通用户生效） */
    private Boolean autoAddContact;
}
