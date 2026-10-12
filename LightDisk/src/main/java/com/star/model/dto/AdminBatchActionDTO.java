package com.star.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理员批量操作请求体（公告等通用 id + 动作）
 */
@Data
public class AdminBatchActionDTO {
    private List<Long> ids;
    /** delete / top / untop / publish / withdraw */
    private String action;
}
