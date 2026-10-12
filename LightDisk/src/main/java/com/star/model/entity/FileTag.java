package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 用户自定义文件标签（一人多标签，uk_user_name 保证同人同名唯一）
 */
@Data
@TableName("file_tag")
public class FileTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String color;

    /** 标签间排序，小在前 */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
