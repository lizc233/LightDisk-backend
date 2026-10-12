package com.star.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class ShareCreateDTO {
    private Long fileId;
    private String extractCode;
    private Integer expireType;
    private Integer expireDays;
    private Integer pageNum = 1;
    private Integer pageSize = 50;
    private List<Long> shareIds;
    /** 批量分享时的文件 id 列表 */
    private List<Long> fileIds;
}
