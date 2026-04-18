package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.util.Date;
import lombok.Data;

@TableName(value ="file")
@Data
public class File {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileName;
    private Long storageId;
    private Long userId;
    private Long folderId;
    private Integer downloadCount;
    private Integer status;          // 1-正常 0-回收站
    private LocalDateTime deleteTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}