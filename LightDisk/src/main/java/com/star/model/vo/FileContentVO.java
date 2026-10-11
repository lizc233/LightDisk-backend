package com.star.model.vo;

import lombok.Data;

/**
 * 文本文件内容 VO（在线编辑器读取）
 */
@Data
public class FileContentVO {

    private Long fileId;

    private String fileName;

    /** 文件内容（UTF-8 文本） */
    private String content;

    /** 当前内容的 MD5（保存时作为 baseMd5 传回，实现乐观锁） */
    private String md5;

    private String ext;

    private Long size;

    /** 是否可编辑（超出大小限制/非文本类型时为 false，仅可只读查看） */
    private Boolean editable;

    /** 不可编辑原因 */
    private String reason;
}
