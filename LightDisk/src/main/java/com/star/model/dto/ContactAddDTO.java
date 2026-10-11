package com.star.model.dto;

import lombok.Data;

/**
 * 添加联系人 DTO
 */
@Data
public class ContactAddDTO {

    /** 联系人用户ID */
    private Long contactId;

    /** 备注名（可选） */
    private String remark;
}
