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

    /** 目标：0-全部用户 1-指定用户 */
    private Integer targetType;

    /** target_type=1 时，指定用户ID列表 */
    private String targetIds;

    /** 状态：0-草稿 1-发布 2-撤回（编辑时用） */
    private Integer status;
}
