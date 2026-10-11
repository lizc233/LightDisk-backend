-- =============================================================
-- 在线编辑器 P2：历史版本表
-- 每次编辑保存 / AI 修改 / 回滚 前，把"旧内容"所在的 storage 快照一行
-- 版本行会"钉住"对应的 storage_file（物理文件不因 ref_count 归零被删）
-- 每个文件最多保留 20 个版本（插入时淘汰最旧）
-- 幂等：CREATE TABLE IF NOT EXISTS
-- =============================================================
USE lightdisk;

CREATE TABLE IF NOT EXISTS `file_content_version` (
  `id`          BIGINT       NOT NULL AUTO_INCREMENT,
  `file_id`     BIGINT       NOT NULL COMMENT '所属文件 id（file 表）',
  `version`     INT          NOT NULL COMMENT '版本号，同一文件内递增',
  `storage_id`  BIGINT       NOT NULL COMMENT '旧内容指向的 storage_file id（内容寻址，天然去重）',
  `file_md5`    VARCHAR(64)  NOT NULL COMMENT '该版本内容 md5（= storage_file.file_md5）',
  `file_size`   BIGINT       DEFAULT 0 COMMENT '该版本字节数',
  `editor_type` TINYINT      DEFAULT 0 COMMENT '0=用户手动保存 1=AI 修改 2=回滚产生',
  `editor_id`   BIGINT       DEFAULT NULL COMMENT '操作用户 id',
  `created_at`  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '保存时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_version` (`file_id`, `version`),
  KEY `idx_fcv_storage` (`storage_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '在线编辑历史版本';
