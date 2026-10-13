package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 视频转码任务表
 * @TableName video_transcode_task
 */
@TableName(value = "video_transcode_task")
@Data
public class VideoTranscodeTask {

    public static final int STATUS_QUEUED = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_DONE = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELED = 4;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long fileId;

    private Long userId;

    private Long storageId;

    private String sourceMd5;

    /** 0-排队中 1-转码中 2-已完成 3-失败 4-已取消 */
    private Integer status;

    private Integer progress;

    private String message;

    private Long durationMs;

    private Integer width;

    private Integer height;

    private Long bitrate;

    private String codec;

    private String outputDir;

    private String masterPlaylist;

    /** 清晰度清单 JSON */
    private String variants;

    private Long outputSize;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
