package com.star.model.dto;

import lombok.Data;

/**
 * 超级管理员：修改用户身份 DTO
 */
@Data
public class AdminUserRoleUpdateDTO {

    /** 目标用户ID */
    private Long userId;

    /** 身份：0-普通用户 1-管理员 2-超级管理员 */
    private Integer isAdmin;
}
