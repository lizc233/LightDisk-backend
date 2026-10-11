package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单向联系人表（通讯录语义：A 加 B 无需 B 同意）
 */
@Data
@TableName("user_contact")
public class UserContact {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户ID */
    private Long userId;

    /** 联系人（对方）用户ID */
    private Long contactId;

    /** 备注名 */
    private String remark;

    private LocalDateTime createdAt;
}
