-- ============================================================
-- 用户头像表
-- 设计说明：
--   1. 头像与网盘文件解耦，单独建表存放，不占用用户网盘配额
--   2. 物理文件放在 <file.upload-dir>/avatars/ 下，按 userId 命名并带时间戳
--      （避免浏览器/CDN 缓存旧头像）
--   3. 每个用户最多一行（uk_user_id），换头像时覆盖该行并删除旧物理文件
-- 执行：mysql --default-character-set=utf8mb4 -uroot -p lightdisk < 2026-10-12_user_avatar.sql
-- ============================================================

CREATE TABLE IF NOT EXISTS `user_avatar` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`    BIGINT       NOT NULL COMMENT '用户ID',
  `real_path`  VARCHAR(512) NOT NULL COMMENT '头像物理文件绝对路径',
  `mime_type`  VARCHAR(64)           DEFAULT NULL COMMENT '图片 MIME 类型',
  `file_size`  BIGINT       NOT NULL DEFAULT 0 COMMENT '文件大小(字节)',
  `created_at` DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '用户头像表';
