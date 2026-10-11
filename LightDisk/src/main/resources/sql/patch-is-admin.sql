-- ============================================================
-- LightDisk 云端数据库补丁（在 Cloud Studio 里执行一次即可）
--
-- 作用：
--   1. 给 user 表补上 is_admin 字段（云端 schema.sql 里没有）
--   2. 建立 admin / 123456 管理员账号
--   3. 补齐 admin 的 user_settings（不补的话个人中心/设置页会报错）
--   4. 校验 vip_config 是否齐全（登录时会 selectById(role)，缺了会报错）
--
-- 特点：**完全幂等**，重复执行多少次都安全，不会报错中断。
--
-- 用法：
--   mysql -uroot -p lightdisk < patch-is-admin.sql
--   或进入 mysql 交互模式后 source 本文件
-- ============================================================


-- ------------------------------------------------------------
-- 1. 补 is_admin 字段（用动态判断，字段已存在也不会报错）
-- ------------------------------------------------------------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME   = 'user'
    AND COLUMN_NAME  = 'is_admin'
);

SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `user` ADD COLUMN `is_admin` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''0-普通用户 1-管理员''',
  'SELECT ''is_admin 字段已存在，跳过'' AS step1'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;


-- ------------------------------------------------------------
-- 2. 建立 / 修复 admin 管理员账号
--    密码 = MD5('123456' + 'admin' + 'LightDisk_Star_#2026')
--         = f35034eabba966725e436bbcd6f209e5
-- ------------------------------------------------------------
INSERT INTO `user`
  (`username`, `password`, `email`, `role`, `is_admin`, `used_space`, `status`, `created_at`, `updated_at`)
VALUES
  ('admin', 'f35034eabba966725e436bbcd6f209e5', NULL, 0, 1, 0, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `password`   = 'f35034eabba966725e436bbcd6f209e5',
  `is_admin`   = 1,
  `status`     = 1,
  `updated_at` = NOW();


-- ------------------------------------------------------------
-- 3. 补齐 admin 的 user_settings
-- ------------------------------------------------------------
INSERT INTO `user_settings` (
  `user_id`, `default_share_expire_days`, `recycle_reminder_enabled`, `recycle_reminder_days`,
  `image_open_action`, `video_open_action`, `doc_open_action`, `other_open_action`
)
SELECT `id`, 15, 1, 3, 'preview', 'preview', 'download', 'download'
FROM `user` u
WHERE u.`username` = 'admin'
  AND NOT EXISTS (SELECT 1 FROM `user_settings` us WHERE us.`user_id` = u.`id`);


-- ------------------------------------------------------------
-- 4. 校验 vip_config 是否齐全（登录会 selectById(role)，缺了会报“用户等级配置不存在”）
-- ------------------------------------------------------------
SELECT `level`, `level_name`, `max_space` FROM `vip_config` ORDER BY `level`;


-- ------------------------------------------------------------
-- 5. 验证结果
-- ------------------------------------------------------------
SELECT `id`, `username`, `password`, `role`, `is_admin`, `status`
FROM `user` WHERE `username` = 'admin';
