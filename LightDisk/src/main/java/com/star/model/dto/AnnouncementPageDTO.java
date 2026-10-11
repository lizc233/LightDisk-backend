package com.star.model.dto;

import lombok.Data;

/**
 * 管理员查询公告列表 DTO
 */
@Data
public class AnnouncementPageDTO {
    /** 页码 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;

    /** 类型筛选：1-公告 2-系统通知 null-全部 */
    private Integer type;

    /** 状态筛选：0-草稿 1-已发布 2-已撤回 null-全部 */
    private Integer status;

    /** 关键词搜索（标题） */
    private String keyword;
}
