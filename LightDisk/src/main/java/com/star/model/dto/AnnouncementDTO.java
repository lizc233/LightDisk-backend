package com.star.model.dto;

import lombok.Data;

/**
 * 创建/编辑公告 DTO
 */
@Data
public class AnnouncementDTO {
    /** 编辑时传ID */
    private Long id;

    /** 标题 */
    private String title;

    /** 内容（支持HTML） */
    private String content;

    /** 类型：1-公告 2-系统通知 */
    private Integer type;

    /** 目标：0-全部用户 1-指定用户 2-指定VIP等级 */
    private Integer targetType;

    /** targetType=1 时为用户ID列表；targetType=2 时为VIP等级列表（均逗号分隔） */
    private String targetIds;

    /** 状态：0-草稿 1-发布 2-撤回（编辑时用） */
    private Integer status;
}
