package com.star.service;

import com.star.model.dto.PersonalMessageSendDTO;
import com.star.model.entity.PersonalMessage;
import com.star.model.vo.PersonalMessageVO;

import java.util.List;
import java.util.Map;

/**
 * 个人消息 Service
 */
public interface PersonalMessageService {

    /**
     * 获取用户个人消息列表
     */
    Map<String, Object> getMessageList(Long userId, Integer pageNum, Integer pageSize);

    /**
     * 管理员：发送私信给指定用户
     */
    List<PersonalMessage> sendPersonalMessage(Long senderId, PersonalMessageSendDTO dto);

    /**
     * 删除个人消息
     */
    void deleteMessage(Long userId, Long messageId);
}
