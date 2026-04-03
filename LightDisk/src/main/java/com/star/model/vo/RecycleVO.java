package com.star.model.vo;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class RecycleVO {
    private Long id;
    private String name;
    private Long size;
    private String type; // file 还是 folder
    private LocalDateTime deleteTime;
    private Integer expireDays; // 剩余有效期（专门为前端准备的计算字段）
}