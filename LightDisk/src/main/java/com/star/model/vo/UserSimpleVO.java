package com.star.model.vo;

import lombok.Data;

/**
 * 用户搜索结果 VO
 * 只暴露最小必要字段（id / username），绝不返回 email 等敏感信息
 */
@Data
public class UserSimpleVO {

    private Long id;

    private String username;

    /** 是否为管理员账号 */
    private Boolean isAdmin;

    /** 身份：0-普通用户 1-管理员 2-超级管理员（管理员搜索接口返回） */
    private Integer adminRole;

    /** 当前登录用户是否已把 TA 加为联系人 */
    private Boolean isContact;

    /** 当前登录用户是否已拉黑 TA */
    private Boolean isBlocked;

    /** 备注名（已加为联系人时有值） */
    private String remark;
}
