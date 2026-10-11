package com.star.service;

import com.star.model.dto.PersonalMessageSendDTO;
import com.star.model.entity.PersonalMessage;

import java.util.List;
import java.util.Map;

/**
 * 个人消息 Service
 */
public interface PersonalMessageService {

    /**
     * 获取用户个人消息列表
     *
     * @param group 分组：all-全部 contact-发送者是我的联系人 stranger-陌生人
     */
    Map<String, Object> getMessageList(Long userId, Integer pageNum, Integer pageSize, String group);

    /**
     * 发送私信（管理员与普通用户通用）
     * message_type 由服务端按发送者角色决定：管理员=2，普通用户=4
     */
    List<PersonalMessage> sendPersonalMessage(Long senderId, PersonalMessageSendDTO dto);

    /**
     * 删除个人消息
     */
    void deleteMessage(Long userId, Long messageId);

    /**
     * 收件人把消息附件转存到自己的网盘根目录
     * 复用分享转存的引用计数逻辑：新 file 记录指向同一物理 storage_file，refCount+1
     *
     * @param userId    当前用户（必须是该消息的收件人）
     * @param messageId 消息ID
     * @param fileId    附件引用的文件ID（必须存在于该消息的 attachments 中）
     * @return 新建 file 记录的 ID
     */
    Long saveAttachmentToMyDisk(Long userId, Long messageId, Long fileId);
}
