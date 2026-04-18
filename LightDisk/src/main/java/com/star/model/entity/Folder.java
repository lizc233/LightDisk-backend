package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.util.Date;
import lombok.Data;

@TableName(value ="folder")
@Data
public class Folder {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String folderName;
    private Long parentId;
    private String path;
    private Long userId;
    private LocalDateTime createdAt;

    private Integer isDeleted;     // 1-正常 0-回收站
    private LocalDateTime deleteTime;
}