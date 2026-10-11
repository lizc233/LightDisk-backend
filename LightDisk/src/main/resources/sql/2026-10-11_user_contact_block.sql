-- =============================================
-- LightDisk 站内私信增强：单向联系人 + 黑名单 + 接收策略
-- 幂等执行：可重复运行
-- =============================================

-- 1. 单向联系人表（通讯录/收藏语义，A 加 B 无需 B 同意）
CREATE TABLE IF NOT EXISTS `user_contact` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    BIGINT      NOT NULL COMMENT '归属用户ID',
    `contact_id` BIGINT      NOT NULL COMMENT '联系人（对方）用户ID',
    `remark`     VARCHAR(50) DEFAULT NULL COMMENT '备注名',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_contact` (`user_id`, `contact_id`),
    INDEX `idx_contact_id` (`contact_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单向联系人表';

-- 2. 黑名单表（谁不想收到谁的消息）
CREATE TABLE IF NOT EXISTS `user_block` (
    `id`              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT   NOT NULL COMMENT '拉黑发起人',
    `blocked_user_id` BIGINT   NOT NULL COMMENT '被拉黑的用户',
    `created_at`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_block` (`user_id`, `blocked_user_id`),
    INDEX `idx_blocked_user` (`blocked_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户黑名单表';

-- 3. user_settings 追加消息接收策略字段（用 information_schema 判重，保证幂等）
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'user_settings'
      AND COLUMN_NAME = 'message_policy'
);
SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE `user_settings` ADD COLUMN `message_policy` TINYINT NOT NULL DEFAULT 0 COMMENT ''接收私信策略：0-所有人 1-仅联系人 2-不接受'' AFTER `other_open_action`',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
