-- =============================================
-- LightDisk 通知系统建表脚本
-- 公告/系统通知 + 已读记录 + 个人消息
-- 幂等执行：可重复运行不会报错
-- =============================================

-- 1. 公告/系统通知表（合一，用 type 区分）
-- type=1 → 侧边栏轮播公告
-- type=2 → 铃铛系统通知
CREATE TABLE IF NOT EXISTS `announcement` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`       VARCHAR(200) NOT NULL COMMENT '标题',
    `content`     TEXT         NOT NULL COMMENT '内容（支持HTML）',
    `type`        TINYINT      NOT NULL DEFAULT 1 COMMENT '类型：1-公告(侧边栏) 2-系统通知(铃铛)',
    `target_type` TINYINT      NOT NULL DEFAULT 0 COMMENT '目标：0-全部用户 1-指定用户',
    `target_ids`  VARCHAR(500) DEFAULT NULL COMMENT 'target_type=1时，指定用户ID列表(逗号分隔)',
    `publisher_id` BIGINT      DEFAULT NULL COMMENT '发布者ID（管理员）',
    `is_top`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否置顶：0-否 1-是',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-草稿 1-已发布 2-已撤回',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_type_status` (`type`, `status`),
    INDEX `idx_is_top` (`is_top`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告/系统通知表';

-- 2. 系统通知已读记录表（多对多关联）
-- 一条系统通知 + 一个用户 = 一条已读记录
CREATE TABLE IF NOT EXISTS `announcement_read` (
    `id`              BIGINT  NOT NULL AUTO_INCREMENT,
    `announcement_id` BIGINT  NOT NULL COMMENT '通知ID',
    `user_id`         BIGINT  NOT NULL COMMENT '用户ID',
    `read_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '阅读时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_announcement_user` (`announcement_id`, `user_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统通知已读记录';

-- 3. 个人消息表（点对点）
-- message_type: 1-系统自动 2-管理员私信 3-分享通知
CREATE TABLE IF NOT EXISTS `personal_message` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `sender_id`   BIGINT       DEFAULT NULL COMMENT '发送者ID（系统消息为NULL）',
    `receiver_id` BIGINT       NOT NULL COMMENT '接收者ID',
    `title`       VARCHAR(200) NOT NULL COMMENT '消息标题',
    `content`     TEXT         NOT NULL COMMENT '消息内容',
    `message_type` TINYINT     NOT NULL DEFAULT 1 COMMENT '类型：1-系统自动 2-管理员私信 3-分享通知',
    `related_id`  BIGINT       DEFAULT NULL COMMENT '关联ID（如分享ID、文件ID）',
    `is_read`     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已读：0-未读 1-已读',
    `read_at`     DATETIME     DEFAULT NULL COMMENT '阅读时间',
    `created_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_receiver_read` (`receiver_id`, `is_read`),
    INDEX `idx_receiver_time` (`receiver_id`, `created_at` DESC),
    INDEX `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个人消息表';

-- =============================================
-- 初始化数据：插入几条示例公告（用于演示）
-- 幂等：按标题判重，重复执行不会产生重复数据
-- publisher_id 动态取管理员账号（is_admin=1）
-- =============================================
INSERT INTO `announcement` (`title`, `content`, `type`, `target_type`, `publisher_id`, `status`)
SELECT '欢迎使用 LightDisk 云盘',
       '<p>LightDisk 是一款轻量级云盘管理系统，支持文件上传下载、分享、回收站、AI 助手等功能。</p><p>如有问题请联系管理员。</p>',
       1, 0, (SELECT id FROM `user` WHERE is_admin = 1 ORDER BY id LIMIT 1), 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `announcement` WHERE `title` = '欢迎使用 LightDisk 云盘');

INSERT INTO `announcement` (`title`, `content`, `type`, `target_type`, `publisher_id`, `status`)
SELECT '系统维护通知',
       '<p>系统将于本周六 02:00-04:00 进行例行维护，届时服务将暂时不可用，请提前保存好工作内容。</p>',
       2, 0, (SELECT id FROM `user` WHERE is_admin = 1 ORDER BY id LIMIT 1), 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `announcement` WHERE `title` = '系统维护通知');
