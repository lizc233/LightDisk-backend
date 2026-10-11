package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 公告/系统通知表
 * type=1 → 侧边栏轮播公告
 * type=2 → 铃铛系统通知
 */
@Data
@TableName("announcement")
public class Announcement {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 标题 */
    private String title;

    /** 内容（支持HTML） */
    private String content;

    /** 类型：1-公告(侧边栏) 2-系统通知(铃铛) */
    private Integer type;

    /** 目标：0-全部用户 1-指定用户 */
    private Integer targetType;

    /** target_type=1时，指定用户ID列表(逗号分隔) */
    private String targetIds;

    /** 发布者ID（管理员） */
    private Long publisherId;

    /** 是否置顶：0-否 1-是 */
    private Integer isTop;

    /** 状态：0-草稿 1-已发布 2-已撤回 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
