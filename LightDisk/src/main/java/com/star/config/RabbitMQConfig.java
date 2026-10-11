package com.star.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置
 *
 * Exchange: lightdisk.direct (Direct Exchange)
 *
 * 路由规则:
 *   share.visited  → 分享被访问通知
 *   email.send     → 异步邮件发送
 *   file.uploaded  → 文件上传后处理（预留）
 *   operation.log  → 操作日志异步写入（预留）
 */
@Configuration
public class RabbitMQConfig {

    // ==================== Exchange ====================

    public static final String EXCHANGE_NAME = "lightdisk.direct";

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    // ==================== Queue 定义 ====================

    /** 分享访问通知队列 */
    public static final String QUEUE_SHARE_VISITED = "lightdisk.share.visited";

    /** 邮件发送队列 */
    public static final String QUEUE_EMAIL_SEND = "lightdisk.email.send";

    /** 文件上传后处理队列（预留） */
    public static final String FILE_UPLOADED = "lightdisk.file.uploaded";

    /** 操作日志队列（预留） */
    public static final String OPERATION_LOG = "lightdisk.operation.log";

    // ==================== Queue Bean ====================

    @Bean
    public Queue shareVisitedQueue() {
        return new Queue(QUEUE_SHARE_VISITED, true, false, false);
    }

    @Bean
    public Queue emailSendQueue() {
        return new Queue(QUEUE_EMAIL_SEND, true, false, false);
    }

    @Bean
    public Queue fileUploadedQueue() {
        return new Queue(FILE_UPLOADED, true, false, false);
    }

    @Bean
    public Queue operationLogQueue() {
        return new Queue(OPERATION_LOG, true, false, false);
    }

    // ==================== Binding ====================

    @Bean
    public Binding bindShareVisited(DirectExchange exchange, Queue shareVisitedQueue) {
        return BindingBuilder.bind(shareVisitedQueue).to(exchange).with("share.visited");
    }

    @Bean
    public Binding bindEmailSend(DirectExchange exchange, Queue emailSendQueue) {
        return BindingBuilder.bind(emailSendQueue).to(exchange).with("email.send");
    }

    @Bean
    public Binding bindFileUploaded(DirectExchange exchange, Queue fileUploadedQueue) {
        return BindingBuilder.bind(fileUploadedQueue).to(exchange).with("file.uploaded");
    }

    @Bean
    public Binding bindOperationLog(DirectExchange exchange, Queue operationLogQueue) {
        return BindingBuilder.bind(operationLogQueue).to(exchange).with("operation.log");
    }

    // ==================== 消息转换器（JSON） ====================

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
