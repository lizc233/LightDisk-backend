package com.star.model.dto;

import lombok.Data;

/**
 * 播放质量上报（前端 VideoPlayer 在播放结束/离开时提交）
 */
@Data
public class QoeReportDTO {

    private Long fileId;

    private String sessionId;

    /** hls / direct */
    private String playType;

    /** 结束时清晰度（auto / 720p ...） */
    private String quality;

    /** 首帧耗时(毫秒) */
    private Integer startupMs;

    /** 本次播放总时长(毫秒) */
    private Long playDurationMs;

    /** 卡顿次数 */
    private Integer stallCount;

    /** 卡顿总时长(毫秒) */
    private Long stallDurationMs;

    /** 清晰度切换次数 */
    private Integer switchCount;

    /** 平均码率(bps) */
    private Long avgBitrate;

    private String userAgent;
}
