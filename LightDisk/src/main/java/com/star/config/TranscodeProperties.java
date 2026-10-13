package com.star.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 视频转码配置（FFmpeg → HLS 多清晰度）
 *
 * <pre>
 * lightdisk:
 *   transcode:
 *     enabled: true
 *     ffmpeg-path: D:/ffmpeg/bin/ffmpeg.exe
 *     ffprobe-path: D:/ffmpeg/bin/ffprobe.exe
 *     output-dir: E:/lightdisk_data/uploads/.hls
 *     max-concurrent: 2
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "lightdisk.transcode")
public class TranscodeProperties {

    /** 总开关；false 时上传视频不投递转码任务，播放回退直连原文件 */
    private boolean enabled = true;

    /** ffmpeg 可执行文件路径，留空则用 PATH 中的 ffmpeg */
    private String ffmpegPath = "";

    /** ffprobe 可执行文件路径，留空则用 PATH 中的 ffprobe */
    private String ffprobePath = "";

    /** HLS 产物根目录 */
    private String outputDir = "E:/lightdisk_data/uploads/.hls";

    /** 单文件转码超时（秒） */
    private long timeoutSeconds = 3600;

    /** 最大并发转码任务数 */
    private int maxConcurrent = 2;

    /** 源视频短于该秒数时跳过转码 */
    private int minDurationSeconds = 1;

    /** HLS 分片时长（秒） */
    private int segmentSeconds = 6;
}
