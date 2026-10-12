package com.star.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员「分享审核」列表行：把分享记录、文件名、分享者拼在一起，
 * 让管理员在同一页就能判断链接是否需要人工失效。
 */
@Data
public class AdminShareVO {
    private Long id;
    private String shareCode;
    private String extractCode;
    private Long fileId;
    private String fileName;
    private Long userId;
    private String username;
    private Integer visitCount;
    /** 1-正常 0-已失效/已撤销 */
    private Integer status;
    /** 是否已过期（过期或已撤销都算失效） */
    private Boolean expired;
    private LocalDateTime createdAt;
    private LocalDateTime expireTime;
}
