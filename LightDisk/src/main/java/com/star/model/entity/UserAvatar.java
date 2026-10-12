package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户头像表
 * 与网盘文件解耦，独立存放，不占用用户存储配额。
 */
@TableName(value = "user_avatar")
@Data
public class UserAvatar {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID（唯一） */
    private Long userId;

    /** 头像物理文件绝对路径 */
    private String realPath;

    /** 图片 MIME 类型 */
    private String mimeType;

    /** 文件大小(字节) */
    private Long fileSize;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
