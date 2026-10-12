package com.star.model.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Data
public class FileListVO {
    private Long id;
    private String name;
    private String type;
    private Long size;
    private String ext;
    private LocalDateTime updateTime;
    private Integer isFavorite;
    /** 文件身上的标签：[{id,name,color}] */
    private List<Map<String, Object>> tags;
}