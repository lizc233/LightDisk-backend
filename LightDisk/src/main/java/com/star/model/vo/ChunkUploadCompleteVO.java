package com.star.model.vo;

import lombok.Data;

/**
 * 分片上传 —— 合并响应。
 */
@Data
public class ChunkUploadCompleteVO {

    private Long fileId;

    /** 合并后的内容命中已有物理文件（去重），未占用额外磁盘 */
    private boolean instant;

    private Long fileSize;

    /** 服务端合并后重新计算的真实 MD5（权威值） */
    private String fileMd5;
}
