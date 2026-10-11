package com.star.consumer;

import com.star.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;
import java.util.Map;

/**
 * 异步邮件发送消费者
 * 验证码、密码重置等邮件通过 RabbitMQ 异步发送
 * 失败可重试，不阻塞主业务流程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailConsumer {

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_EMAIL_SEND)
    public void handleEmailSend(Map<String, Object> payload, Message message, Channel channel) throws Exception {
        try {
            log.info("【邮件发送】收到消息: {}", payload);

            String to = (String) payload.get("to");
            String subject = (String) payload.get("subject");
            String content = (String) payload.get("content");

            if (mailSender == null) {
                log.warn("【邮件发送】邮件服务未配置（lightdisk.mail.enabled=false），跳过发送");
                log.info("【邮件发送】模拟发送: to={}, subject={}", to, subject);
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }

            SimpleMailMessage mailMessage = new SimpleMailMessage();
            mailMessage.setTo(to);
            mailMessage.setSubject(subject);
            mailMessage.setText(content);
            mailMessage.setFrom(payload.getOrDefault("from", "noreply@lightdisk.com").toString());

            mailSender.send(mailMessage);
            log.info("【邮件发送】成功: to={}", to);

            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);

        } catch (Exception e) {
            log.error("【邮件发送】失败，即将重试", e);
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
        }
    }
}
