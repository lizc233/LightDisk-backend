package com.star.consumer;

import com.star.config.RabbitMQConfig;
import com.star.mapper.PersonalMessageMapper;
import com.star.model.entity.PersonalMessage;
import com.star.service.impl.NotificationServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import com.rabbitmq.client.Channel;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 分享访问通知消费者
 * 当有人通过分享链接下载/查看文件时，RabbitMQ 投递消息到此队列
 * 消费者负责：给文件主人创建一条个人消息 + 更新 Redis 未读缓存
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShareNotifyConsumer {

    private final PersonalMessageMapper personalMessageMapper;
    private final NotificationServiceImpl notificationService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_SHARE_VISITED)
    public void handleShareVisited(Map<String, Object> payload, Message message, Channel channel) throws Exception {
        try {
            log.info("【分享通知】收到消息: {}", payload);

            Long ownerId = getLong(payload, "ownerId");
            String visitorName = (String) payload.get("visitorName");
            String fileName = (String) payload.get("fileName");
            String shareCode = (String) payload.get("shareCode");
            Long shareId = getLong(payload, "shareId");

            if (ownerId == null) {
                log.warn("【分享通知】缺少 ownerId，跳过处理");
                return;
            }

            // 创建个人消息（type=3 分享通知）
            PersonalMessage msg = new PersonalMessage();
            msg.setSenderId(null); // 系统消息
            msg.setReceiverId(ownerId);
            msg.setTitle("您的文件被访问了");
            msg.setContent(String.format(
                    "用户 <b>%s</b> 通过分享链接查看了您的文件 <b>%s</b>。",
                    visitorName != null ? visitorName : "匿名用户",
                    fileName != null ? fileName : "未知文件"
            ));
            msg.setMessageType(3); // 分享通知
            msg.setRelatedId(shareId);
            msg.setIsRead(0);
            msg.setCreatedAt(LocalDateTime.now());

            personalMessageMapper.insert(msg);
            log.info("【分享通知】已向用户 {} 发送分享访问通知", ownerId);

            // Redis 未读数 +1
            notificationService.incrementUnreadCache(ownerId);

            // 手动确认消息
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);

        } catch (Exception e) {
            log.error("【分享通知】处理失败，即将重试", e);
            // 拒绝消息并重新入队（触发重试机制）
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }

    private Long getLong(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return null; }
    }
}
