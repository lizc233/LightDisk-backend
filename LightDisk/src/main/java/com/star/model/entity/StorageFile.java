package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.util.Date;
import lombok.Data;

@TableName(value ="storage_file")
@Data
public class StorageFile {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileMd5;
    private String realPath;
    private Long fileSize;
    private String fileType;
    private Integer refCount;
    private LocalDateTime createdAt;

    private Long firstUploadUser;
    private String fileExtension;
}