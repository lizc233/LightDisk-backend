package com.star.model.dto;

import lombok.Data;

/**
 * 分片上传 —— 合并请求。
 */
@Data
public class ChunkUploadCompleteDTO {

    private String uploadId;
}
