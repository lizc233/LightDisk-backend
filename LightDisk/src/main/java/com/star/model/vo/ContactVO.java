package com.star.model.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 联系人列表项 VO
 */
@Data
public class ContactVO {

    /** 记录ID */
    private Long id;

    /** 联系人用户ID */
    private Long userId;

    /** 联系人用户名 */
    private String username;

    /** 备注名 */
    private String remark;

    private LocalDateTime createdAt;
}
