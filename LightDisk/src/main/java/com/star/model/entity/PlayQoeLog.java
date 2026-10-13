package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 播放质量日志（QoE）
 * @TableName play_qoe_log
 */
@TableName(value = "play_qoe_log")
@Data
public class PlayQoeLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fileId;

    private Long userId;

    private String sessionId;

    /** hls / direct */
    private String playType;

    private String quality;

    private Integer startupMs;

    private Long playDurationMs;

    private Integer stallCount;

    private Long stallDurationMs;

    private BigDecimal rebufferRatio;

    private Integer switchCount;

    private Long avgBitrate;

    private Integer vipLevel;

    private String userAgent;

    private LocalDateTime createdAt;
}
