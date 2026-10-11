package com.star.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.star.context.BaseContext;
import com.star.exception.BusinessException;
import com.star.mapper.PersonalMessageMapper;
import com.star.mapper.UserMapper;
import com.star.model.dto.PersonalMessageSendDTO;
import com.star.model.entity.PersonalMessage;
import com.star.model.entity.User;
import com.star.model.vo.PersonalMessageVO;
import com.star.service.NotificationService;
import com.star.service.PersonalMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalMessageServiceImpl implements PersonalMessageService {

    private final PersonalMessageMapper personalMessageMapper;
    private final UserMapper userMapper;
    private final NotificationServiceImpl notificationService; // 用于刷新缓存

    @Override
    public Map<String, Object> getMessageList(Long userId, Integer pageNum, Integer pageSize) {
        Page<PersonalMessage> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<PersonalMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PersonalMessage::getReceiverId, userId)
                .orderByDesc(PersonalMessage::getCreatedAt);
        Page<PersonalMessage> result = personalMessageMapper.selectPage(page, wrapper);

        List<PersonalMessageVO> voList = result.getRecords().stream().map(this::toVO).collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("list", voList);
        data.put("total", result.getTotal());
        data.put("pageNum", result.getCurrent());
        data.put("pageSize", result.getSize());
        return data;
    }

    @Override
    @Transactional
    public List<PersonalMessage> sendPersonalMessage(Long senderId, PersonalMessageSendDTO dto) {
        if (dto.getReceiverIds() == null || dto.getReceiverIds().isBlank()) {
            throw new BusinessException("接收者ID不能为空");
        }
        if (dto.getTitle() == null || dto.getTitle().isBlank()) {
            throw new BusinessException("消息标题不能为空");
        }
        if (dto.getContent() == null || dto.getContent().isBlank()) {
            throw new BusinessException("消息内容不能为空");
        }

        String[] receiverIdArr = dto.getReceiverIds().split(",");
        List<PersonalMessage> messages = new ArrayList<>();

        for (String ridStr : receiverIdArr) {
            Long receiverId = Long.parseLong(ridStr.trim());

            // 验证接收者存在
            User receiver = userMapper.selectById(receiverId);
            if (receiver == null) {
                log.warn("发送私信失败：用户 {} 不存在", receiverId);
                continue;
            }

            PersonalMessage msg = new PersonalMessage();
            msg.setSenderId(senderId);
            msg.setReceiverId(receiverId);
            msg.setTitle(dto.getTitle().trim());
            msg.setContent(dto.getContent().trim());
            msg.setMessageType(2); // 管理员私信
            msg.setIsRead(0);
            msg.setCreatedAt(LocalDateTime.now());

            personalMessageMapper.insert(msg);
            messages.add(msg);

            // Redis 未读数 +1
            notificationService.incrementUnreadCache(receiverId);
        }

        log.info("管理员 [{}] 发送私信给 {} 个用户，标题: {}", senderId, messages.size(), dto.getTitle());
        return messages;
    }

    @Override
    @Transactional
    public void deleteMessage(Long userId, Long messageId) {
        PersonalMessage msg = personalMessageMapper.selectById(messageId);
        if (msg == null) {
            throw new BusinessException("消息不存在");
        }
        if (!msg.getReceiverId().equals(userId)) {
            throw new BusinessException("无权删除此消息");
        }
        personalMessageMapper.deleteById(messageId);
    }

    private PersonalMessageVO toVO(PersonalMessage entity) {
        PersonalMessageVO vo = new PersonalMessageVO();
        vo.setId(entity.getId());
        vo.setSenderId(entity.getSenderId());
        vo.setTitle(entity.getTitle());
        vo.setContent(entity.getContent());
        vo.setMessageType(entity.getMessageType());
        vo.setRelatedId(entity.getRelatedId());
        vo.setIsRead(entity.getIsRead());
        vo.setReadAt(entity.getReadAt());
        vo.setCreatedAt(entity.getCreatedAt());

        // 填充发送者名称
        if (entity.getSenderId() != null) {
            User sender = userMapper.selectById(entity.getSenderId());
            vo.setSenderName(sender != null ? sender.getUsername() : "未知用户");
        } else {
            vo.setSenderName("系统");
        }
        return vo;
    }
}
