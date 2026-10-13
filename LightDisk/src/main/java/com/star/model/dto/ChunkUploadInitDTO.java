package com.star.model.dto;

import lombok.Data;

/**
 * 分片上传 —— 初始化请求。
 *
 * <p>由客户端发起：先声明文件元信息，服务端据此判断能否秒传，
 * 并返回已接收的分片索引，实现断点续传。</p>
 */
@Data
public class ChunkUploadInitDTO {

    /**
     * 客户端生成的上传会话 ID（由 userId + 文件名 + 大小 + 修改时间 + 分片大小 派生）。
     * 同一文件重复上传会得到同一个 ID，服务端因此可以复用已落盘的分片。
     * 仅允许 [A-Za-z0-9_-]，长度 8~64，防止路径穿越。
     */
    private String uploadId;

    private String fileName;

    private Long fileSize;

    /** 分片大小（字节） */
    private Long chunkSize;

    /** 分片总数 */
    private Integer totalChunks;

    private Long folderId;

    /**
     * 文件整体 MD5（前端 spark-md5 增量计算）。
     * 非空时用于**秒传前移**：服务端已有同 MD5 文件则直接建记录，用户一个字节都不用传。
     */
    private String fileMd5;
}
