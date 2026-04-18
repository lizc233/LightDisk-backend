package com.star.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;

@Data
public class FileListVO {
    private Long id;
    private String name;
    private String type;
    private Long size;
    private String ext;
    private LocalDateTime updateTime;
}