package com.star.model.dto;

import lombok.Data;

@Data
public class AdminUserVipUpdateDTO {
    private Long userId;
    private Integer role;
    private Integer durationDays;
}
