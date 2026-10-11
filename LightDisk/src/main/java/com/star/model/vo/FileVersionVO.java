package com.star.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 历史版本列表项（编辑器 P2）
 */
@Data
public class FileVersionVO {

    private Long id;

    /** 版本号 */
    private Integer version;

    /** 内容 md5（即当时的文件内容指纹） */
    private String fileMd5;

    /** 字节数 */
    private Long fileSize;

    /** 0=用户手动 1=AI 修改 2=回滚产生 */
    private Integer editorType;

    /** 展示名：用户保存 / AI 修改 / 版本回滚 */
    private String editorTypeName;

    private LocalDateTime createdAt;

    /** 是否是当前文件正指向的内容 */
    private Boolean current;
}
