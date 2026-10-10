package com.star.model.dto;

import lombok.Data;

@Data
public class AdminUserPageDTO {
    private Integer pageNum;
    private Integer pageSize;
    private String keyword;
    private Integer role;
    private Integer status;
    private Integer isAdmin;
}
