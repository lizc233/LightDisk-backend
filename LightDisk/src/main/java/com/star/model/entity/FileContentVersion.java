package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线编辑历史版本（编辑器 P2）。
 * <p>每次保存 / AI 修改 / 回滚前，把「旧内容」所在的 storage 快照成一行。
 * 版本行会钉住对应 storage_file：即使 ref_count 归零，物理文件也保留，
 * 这样任意版本都能回滚（内容寻址存储保证多版本相同内容自动去重，不占双倍空间）。
 */
@Data
@TableName("file_content_version")
public class FileContentVersion {

    /** 产生原因：0=用户手动保存 1=AI 修改 2=回滚产生 */
    public static final int EDITOR_USER = 0;
    public static final int EDITOR_AI = 1;
    public static final int EDITOR_ROLLBACK = 2;

    /** 每个文件最多保留的版本数（超出淘汰最旧） */
    public static final int MAX_VERSIONS_PER_FILE = 20;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文件 id（file 表） */
    private Long fileId;

    /** 版本号，同一文件内递增 */
    private Integer version;

    /** 旧内容指向的 storage_file id */
    private Long storageId;

    /** 该版本内容 md5 */
    private String fileMd5;

    /** 该版本字节数 */
    private Long fileSize;

    /** 0=用户手动 1=AI 2=回滚 */
    private Integer editorType;

    /** 操作用户 id */
    private Long editorId;

    private LocalDateTime createdAt;
}
