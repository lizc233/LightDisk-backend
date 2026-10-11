package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统通知已读记录表（多对多关联）
 * 一条系统通知 + 一个用户 = 一条已读记录
 */
@Data
@TableName("announcement_read")
public class AnnouncementRead {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 通知ID */
    private Long announcementId;

    /** 用户ID */
    private Long userId;

    /** 阅读时间 */
    private LocalDateTime readAt;
}
