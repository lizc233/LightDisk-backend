package com.star.model.dto;

import lombok.Data;

/**
 * 管理员「分享审核」查询条件
 */
@Data
public class AdminShareQueryDTO {
    /** 分享者用户名（模糊） */
    private String username;
    /** 文件名 / 分享码（模糊） */
    private String keyword;
    /** 1-正常 0-已失效；null 表示全部 */
    private Integer status;
    /** true 只看已过期的正常分享 */
    private Boolean onlyExpired;
    private Integer page = 1;
    private Integer pageSize = 20;
}
