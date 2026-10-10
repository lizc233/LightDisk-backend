package com.star.model.vo;

import lombok.Data;

@Data
public class AiSourceVO {
    private String sourceType;
    private Long sourceId;
    private String title;
    private String path;
    private String summary;
}
