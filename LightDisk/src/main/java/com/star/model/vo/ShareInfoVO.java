package com.star.model.vo;

import lombok.Data;

@Data
public class ShareInfoVO {
    private Long id;
    private Long fileId;
    private String fileName;
    private String shareUrl;
    private String extractCode;
    private Integer views;
    private String shareTime;
    private String expireTime;
    private Integer status;
    private Boolean expired;
}
