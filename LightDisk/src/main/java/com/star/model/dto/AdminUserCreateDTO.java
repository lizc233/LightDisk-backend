package com.star.model.dto;

import lombok.Data;

/**
 * 超级管理员：添加用户 DTO
 */
@Data
public class AdminUserCreateDTO {

    /** 用户名（唯一） */
    private String username;

    /** 初始密码（明文，服务端加密存储） */
    private String password;

    /** 邮箱（可选） */
    private String email;

    /** VIP 等级：0-普通用户（默认） */
    private Integer vipRole;

    /** 身份：0-普通用户 1-管理员 2-超级管理员（默认0） */
    private Integer isAdmin;
}
