package com.star.model.vo;

import lombok.Data;

/**
 * 批量下载时的一个 zip 条目：zip 内相对路径 + 物理文件绝对路径
 */
@Data
public class BatchDownloadItemVO {
    /** zip 内的相对路径，如「照片/2026/a.jpg」 */
    private String entryName;
    /** 物理文件绝对路径 */
    private String realPath;
    /** 原始文件大小（用于日志/统计） */
    private Long size;
}
