package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 标签-文件 多对多关系（uk_tag_file 防重复打标）
 */
@Data
@TableName("file_tag_relation")
public class FileTagRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tagId;

    private Long fileId;

    private LocalDateTime createdAt;
}
