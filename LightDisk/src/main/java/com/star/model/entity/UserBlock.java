package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户黑名单表（user_id 不愿再收到 blocked_user_id 的消息）
 */
@Data
@TableName("user_block")
public class UserBlock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 拉黑发起人 */
    private Long userId;

    /** 被拉黑的用户 */
    private Long blockedUserId;

    private LocalDateTime createdAt;
}
