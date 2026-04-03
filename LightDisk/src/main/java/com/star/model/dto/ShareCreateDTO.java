package com.star.model.dto;

import lombok.Data;

@Data
public class ShareCreateDTO {
    private Long fileId;      // 要分享的文件ID
    private Integer expireType; // 过期类型：0永久，1七天，2三十天
}