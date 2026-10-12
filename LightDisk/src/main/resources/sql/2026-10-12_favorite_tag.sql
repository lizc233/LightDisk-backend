-- ============================================================
-- 收藏/星标 + 文件标签系统 + 快速筛选拖拽顺序（2026-10-12）
-- 执行：mysql --default-character-set=utf8mb4 -uroot -p lightdisk < 2026-10-12_favorite_tag.sql
-- ============================================================

-- 1) 收藏：file 本就一人一行（秒传去重只作用于 storage_file），直接加字段零 join
ALTER TABLE `file`
    ADD COLUMN `is_favorite` TINYINT NOT NULL DEFAULT 0 COMMENT '是否收藏 0-否 1-是',
    ADD COLUMN `favorite_time` DATETIME NULL COMMENT '收藏时间',
    ADD INDEX `idx_user_fav` (`user_id`, `is_favorite`);

-- 2) 用户自定义标签
CREATE TABLE IF NOT EXISTS `file_tag` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT       NOT NULL COMMENT '所属用户',
    `name`       VARCHAR(32)  NOT NULL COMMENT '标签名',
    `color`      VARCHAR(16)  NOT NULL DEFAULT '#0F6E56' COMMENT '标签色',
    `sort_order` INT          NOT NULL DEFAULT 0 COMMENT '标签间排序（小在前）',
    `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_name` (`user_id`, `name`),
    KEY `idx_user_sort` (`user_id`, `sort_order`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户自定义文件标签';

-- 3) 标签-文件 多对多关系
CREATE TABLE IF NOT EXISTS `file_tag_relation` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `tag_id`     BIGINT   NOT NULL,
    `file_id`    BIGINT   NOT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tag_file` (`tag_id`, `file_id`),
    KEY `idx_file` (`file_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '标签与文件的多对多关系';

-- 4) 快速筛选区拖拽顺序（跨类型 JSON：["type:image","fav","tag:12",...]）
ALTER TABLE `user_settings`
    ADD COLUMN `sidebar_order` VARCHAR(2000) NULL COMMENT '快速筛选区条目顺序 JSON';
