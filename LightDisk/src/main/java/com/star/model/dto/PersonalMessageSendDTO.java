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

    /**
     * 附件 JSON 数组字符串（可选），元素结构：
     * [{"fileId":123,"name":"报告.pdf","size":102400,"type":"pdf"}]
     * 服务端会校验 JSON 合法性并限制长度
     */
    private String attachments;

    /** 发送成功后是否把接收者自动加为联系人（仅普通用户生效） */
    private Boolean autoAddContact;
}
