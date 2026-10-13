package com.star.consumer;

import com.rabbitmq.client.Channel;
import com.star.config.RabbitMQConfig;
import com.star.service.IVideoTranscodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 视频转码消费者
 *
 * <p>上传视频后投递 {@code video.transcode} 路由键，由本消费者触发
 * FFmpeg 多清晰度 HLS 转码；失败 Nack 重入队列（application.yml 已配 retry max-attempts=3），
 * 最终仍失败时任务表状态由服务内部回写为 FAILED，前端可见失败原因并支持手动重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoTranscodeConsumer {

    private final IVideoTranscodeService videoTranscodeService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_VIDEO_TRANSCODE)
    public void handleTranscode(Map<String, Object> payload, Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        Long taskId = null;
        try {
            Object raw = payload.get("taskId");
            if (raw instanceof Number) {
                taskId = ((Number) raw).longValue();
            } else if (raw != null) {
                taskId = Long.valueOf(raw.toString());
            }
            if (taskId == null) {
                log.warn("【转码消费】消息缺少 taskId，直接确认丢弃: {}", payload);
                channel.basicAck(tag, false);
                return;
            }
            log.info("【转码消费】开始处理 taskId={}", taskId);
            videoTranscodeService.execute(taskId);
            channel.basicAck(tag, false);
            log.info("【转码消费】处理结束 taskId={}", taskId);
        } catch (Exception e) {
            log.error("【转码消费】异常，稍后重试 taskId={}", taskId, e);
            // 重新入队（配合 retry 策略），避免消息丢失；超过重试上限后进死信/丢弃
            channel.basicNack(tag, false, true);
        }
    }
}
