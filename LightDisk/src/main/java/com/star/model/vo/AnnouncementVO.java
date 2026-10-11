package com.star.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告/通知 VO（列表用）
 */
@Data
public class AnnouncementVO {
    private Long id;
    private String title;
    private String content;
    /** 类型：1-公告 2-系统通知 */
    private Integer type;
    /** 目标：0-全部用户 1-指定用户 2-指定VIP等级 */
    private Integer targetType;
    /** targetType=1 用户ID列表；=2 VIP等级列表（逗号分隔） */
    private String targetIds;
    /** 是否置顶 */
    private Integer isTop;
    /** 状态 */
    private Integer status;
    /** 发布者用户名 */
    private String publisherName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 以下字段仅前端展示用 =====
    /** 当前用户是否已读（仅 type=2 系统通知时有值） */
    private Boolean isRead = false;
}
