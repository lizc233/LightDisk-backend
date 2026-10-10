package com.star.model.dto;

import lombok.Data;

@Data
public class AdminUserStatusUpdateDTO {
    private Long userId;
    private Integer status;
}
