CREATE TABLE IF NOT EXISTS `user_settings` (
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `default_share_expire_days` INT NOT NULL DEFAULT 15 COMMENT '默认分享有效期，支持 7/15/30',
  `recycle_reminder_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否开启回收站提醒，1开启 0关闭',
  `recycle_reminder_days` INT NOT NULL DEFAULT 3 COMMENT '回收站提前提醒天数',
  `image_open_action` VARCHAR(20) NOT NULL DEFAULT 'preview' COMMENT '图片打开方式：preview/download',
  `video_open_action` VARCHAR(20) NOT NULL DEFAULT 'preview' COMMENT '视频打开方式：preview/download',
  `doc_open_action` VARCHAR(20) NOT NULL DEFAULT 'download' COMMENT '文档打开方式：preview/download',
  `other_open_action` VARCHAR(20) NOT NULL DEFAULT 'download' COMMENT '其他文件打开方式：preview/download',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户设置表';

INSERT INTO `user_settings` (
  `user_id`,
  `default_share_expire_days`,
  `recycle_reminder_enabled`,
  `recycle_reminder_days`,
  `image_open_action`,
  `video_open_action`,
  `doc_open_action`,
  `other_open_action`
)
SELECT
  `id`,
  15,
  1,
  3,
  'preview',
  'preview',
  'download',
  'download'
FROM `user` u
WHERE NOT EXISTS (
  SELECT 1 FROM `user_settings` us WHERE us.`user_id` = u.`id`
);
