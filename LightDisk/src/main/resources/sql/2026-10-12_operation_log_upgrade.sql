-- 操作日志功能升级：增加动作类型与关联对象，补齐筛选用索引
-- 执行方式：mysql --default-character-set=utf8mb4 -uroot -p lightdisk < 2026-10-12_operation_log_upgrade.sql
USE lightdisk;

ALTER TABLE operation_log
    ADD COLUMN action VARCHAR(50) NOT NULL DEFAULT '' COMMENT '动作类型: login/upload/download/delete/share_send/share_cancel/share_reject...' AFTER module,
    ADD COLUMN target_id BIGINT DEFAULT NULL COMMENT '关联业务ID(如分享ID)' AFTER action,
    ADD INDEX idx_module (module),
    ADD INDEX idx_action (action),
    ADD INDEX idx_created_at (created_at);
