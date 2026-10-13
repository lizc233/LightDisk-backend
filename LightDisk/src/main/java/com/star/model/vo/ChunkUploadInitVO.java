package com.star.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 分片上传 —— 初始化响应。
 */
@Data
public class ChunkUploadInitVO {

    private String uploadId;

    /** 命中秒传：服务端已有相同 MD5 的物理文件，fileId 即为新建的文件记录 */
    private boolean instant;

    /** 秒传时才返回 */
    private Long fileId;

    private Long chunkSize;

    private Integer totalChunks;

    /** 服务端已落盘的分片索引，前端据此跳过这些分片（断点续传） */
    private List<Integer> received;

    /** 已上传字节数（按分片推算，用于进度条初始值） */
    private Long uploadedBytes;
}
