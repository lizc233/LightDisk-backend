-- =====================================================================
-- 第三批：视频转码（HLS 多清晰度） + 播放质量看板（QoE） + VIP 权限矩阵
-- 日期：2026-10-13
-- 字符集：utf8mb4（导入请带 --default-character-set=utf8mb4）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 转码任务表：一条 file 记录最多一条有效任务行（重新转码会覆盖）
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `video_transcode_task` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `file_id`       BIGINT       NOT NULL COMMENT '业务文件ID（file.id）',
  `user_id`       BIGINT       NOT NULL COMMENT '所属用户ID',
  `storage_id`    BIGINT       NOT NULL COMMENT '源文件存储ID（storage_file.id），用于判断源是否变更',
  `source_md5`    VARCHAR(64)  DEFAULT NULL COMMENT '源文件MD5，源变更时任务自动失效',
  `status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '0-排队中 1-转码中 2-已完成 3-失败 4-已取消',
  `progress`      INT          NOT NULL DEFAULT 0 COMMENT '进度 0-100',
  `message`       VARCHAR(255) DEFAULT NULL COMMENT '状态描述 / 失败原因',
  `duration_ms`   BIGINT       DEFAULT NULL COMMENT '视频时长(毫秒)',
  `width`         INT          DEFAULT NULL COMMENT '源视频宽',
  `height`        INT          DEFAULT NULL COMMENT '源视频高',
  `bitrate`       BIGINT       DEFAULT NULL COMMENT '源视频码率(bps)',
  `codec`         VARCHAR(32)  DEFAULT NULL COMMENT '源视频编码',
  `output_dir`    VARCHAR(500) DEFAULT NULL COMMENT 'HLS 产物目录（绝对路径）',
  `master_playlist` VARCHAR(500) DEFAULT NULL COMMENT 'master.m3u8 绝对路径',
  `variants`      TEXT         DEFAULT NULL COMMENT '清晰度清单 JSON：[{"name":"720p","width":1280,"height":720,"bandwidth":2800000,"playlist":"720p.m3u8"}]',
  `output_size`   BIGINT       NOT NULL DEFAULT 0 COMMENT '产物总字节数',
  `started_at`    DATETIME     DEFAULT NULL COMMENT '开始转码时间',
  `finished_at`   DATETIME     DEFAULT NULL COMMENT '结束时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_id` (`file_id`),
  KEY `idx_status` (`status`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='视频转码任务表';


-- ---------------------------------------------------------------------
-- 2. 播放质量日志（QoE）：前端 player 上报，用于播放看板聚合
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `play_qoe_log` (
  `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  `file_id`           BIGINT      NOT NULL COMMENT '文件ID',
  `user_id`           BIGINT      NOT NULL COMMENT '用户ID',
  `session_id`        VARCHAR(64) DEFAULT NULL COMMENT '一次播放会话ID（前端生成）',
  `play_type`         VARCHAR(16) NOT NULL DEFAULT 'hls' COMMENT 'hls / direct',
  `quality`           VARCHAR(16) DEFAULT NULL COMMENT '结束时清晰度（auto / 720p ...）',
  `startup_ms`        INT         NOT NULL DEFAULT 0 COMMENT '首帧耗时(毫秒)',
  `play_duration_ms`  BIGINT      NOT NULL DEFAULT 0 COMMENT '本次播放总时长(毫秒)',
  `stall_count`       INT         NOT NULL DEFAULT 0 COMMENT '卡顿次数',
  `stall_duration_ms` BIGINT      NOT NULL DEFAULT 0 COMMENT '卡顿总时长(毫秒)',
  `rebuffer_ratio`    DECIMAL(6,4) NOT NULL DEFAULT 0 COMMENT '卡顿时长/播放时长',
  `switch_count`      INT         NOT NULL DEFAULT 0 COMMENT '清晰度切换次数',
  `avg_bitrate`       BIGINT      NOT NULL DEFAULT 0 COMMENT '平均码率(bps)',
  `vip_level`         INT         NOT NULL DEFAULT 0 COMMENT '播放时用户等级',
  `user_agent`        VARCHAR(255) DEFAULT NULL COMMENT '浏览器 UA 摘要',
  `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上报时间',
  PRIMARY KEY (`id`),
  KEY `idx_file` (`file_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_created` (`created_at`),
  KEY `idx_startup` (`startup_ms`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='播放质量日志(QoE)';


-- ---------------------------------------------------------------------
-- 3. VIP 权限矩阵：清晰度上限 / 倍速上限 / 并发播放流上限
--    说明：max_video_quality = 允许的最高清晰度高度（360/720/1080），0 表示只能看最低档
--         max_playback_rate = 允许的最大倍速（1.00 表示不允许倍速）
--         max_concurrent_stream = 同时进行的播放流数量上限
-- ---------------------------------------------------------------------
ALTER TABLE `vip_config`
  ADD COLUMN `max_video_quality`     INT   NOT NULL DEFAULT 360  COMMENT '允许的最高清晰度高度(px)：360/720/1080' AFTER `download_speed_limit`,
  ADD COLUMN `max_playback_rate`     DECIMAL(4,2) NOT NULL DEFAULT 1.00 COMMENT '允许的最大播放倍速（1.00 表示不支持倍速）' AFTER `max_video_quality`,
  ADD COLUMN `max_concurrent_stream` INT   NOT NULL DEFAULT 1    COMMENT '同时播放流上限' AFTER `max_playback_rate`;

-- 权限矩阵默认值（按现有等级阶梯）
UPDATE `vip_config` SET `max_video_quality` = 360,  `max_playback_rate` = 1.00, `max_concurrent_stream` = 1 WHERE `level` = 0;
UPDATE `vip_config` SET `max_video_quality` = 720,  `max_playback_rate` = 1.25, `max_concurrent_stream` = 2 WHERE `level` = 1;
UPDATE `vip_config` SET `max_video_quality` = 1080, `max_playback_rate` = 1.50, `max_concurrent_stream` = 3 WHERE `level` = 2;
UPDATE `vip_config` SET `max_video_quality` = 1080, `max_playback_rate` = 2.00, `max_concurrent_stream` = 5 WHERE `level` = 3;

-- 校验
SELECT `level`, `level_name`, `max_video_quality`, `max_playback_rate`, `max_concurrent_stream` FROM `vip_config` ORDER BY `level`;
