-- ============================================================
-- LightDisk 管理员账号初始化 / 修复脚本
-- 适用：本地、cloudstudio.net 线上库均可执行
-- 目标账号：admin / 123456（管理员）
--
-- 说明：管理员身份统一用 user.is_admin 字段判定（1=管理员 2=超级管理员）。
--       云端 schema.sql 里原本没有该字段，本脚本负责补上。
--       本脚本幂等，可重复执行。
-- ============================================================

-- 【第一步】确保 user 表存在 is_admin 字段（老库/云端 schema.sql 都没有）
-- 若字段已存在，MySQL 会报 1060 错误，可忽略，继续执行下面的语句。
ALTER TABLE `user`
  ADD COLUMN `is_admin` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0-普通用户 1-管理员 2-超级管理员';

-- 【第二步】插入或修复 admin 账号
-- 密码规则：MD5(明文 + 用户名 + 固定盐 "LightDisk_Star_#2026")
-- 即 MD5('123456' + 'admin' + 'LightDisk_Star_#2026')
--      = f35034eabba966725e436bbcd6f209e5
INSERT INTO `user` (`username`, `password`, `email`, `role`, `is_admin`, `used_space`, `status`, `created_at`, `updated_at`)
VALUES ('admin', 'f35034eabba966725e436bbcd6f209e5', NULL, 0, 2, 0, 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE
  `password`  = 'f35034eabba966725e436bbcd6f209e5',
  `is_admin`  = 2,
  `status`    = 1,
  `updated_at` = NOW();

-- 【第三步】给 admin 补齐 user_settings（缺失时个人中心/设置页会报错）
INSERT INTO `user_settings` (
  `user_id`, `default_share_expire_days`, `recycle_reminder_enabled`, `recycle_reminder_days`,
  `image_open_action`, `video_open_action`, `doc_open_action`, `other_open_action`
)
SELECT `id`, 15, 1, 3, 'preview', 'preview', 'download', 'download'
FROM `user` u
WHERE u.`username` = 'admin'
  AND NOT EXISTS (SELECT 1 FROM `user_settings` us WHERE us.`user_id` = u.`id`);

-- 【第四步】检查用户等级配置（登录时会读取 vip_config，缺失会报“用户等级配置不存在”）
SELECT * FROM `vip_config` ORDER BY `level`;

-- 【验证】
SELECT `id`, `username`, `password`, `role`, `is_admin`, `status` FROM `user` WHERE `username` = 'admin';
