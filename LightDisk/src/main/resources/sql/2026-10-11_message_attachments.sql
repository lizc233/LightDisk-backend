-- =============================================================
-- 私信附件支持（第一阶段：表结构准备）
-- attachments 为 JSON 数组字符串，元素结构：
--   [{"fileId":123,"name":"报告.pdf","size":102400,"type":"pdf"}]
-- fileId 指向 file 表（发送者自己的网盘文件），后续可扩展
-- "接收者一键转存到我的网盘"（复用 savePublicShareToMyDisk 的引用计数思路）
-- 幂等：可重复执行
-- =============================================================
USE lightdisk;

SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'lightdisk'
      AND TABLE_NAME = 'personal_message'
      AND COLUMN_NAME = 'attachments'
);
SET @ddl = IF(
    @col_exists = 0,
    'ALTER TABLE `personal_message` ADD COLUMN `attachments` TEXT NULL COMMENT ''附件JSON数组'' AFTER `content`',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
