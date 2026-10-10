package com.star.model.dto;

import lombok.Data;

@Data
public class LoginDTO {
    private String username;
    private String password;

    /**
     * 是否以管理员身份登录。
     * 前端勾选“我是管理员”后置为 true，此时后端的密码校验逻辑不同（见 UserServiceImpl#login）。
     */
    private Boolean adminLogin;
}
