package com.star.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 管理员批量操作请求体（用户维度）
 */
@Data
public class AdminUserBatchDTO {
    /** 选中的用户 id */
    private List<Long> userIds;
    /** 批量启停：1-启用 0-禁用 */
    private Integer status;
    /** 批量调整 VIP 的目标等级 */
    private Integer role;
    /** 批量调整 VIP 的时长（天） */
    private Integer durationDays;
    /** 批量添加用户时的候选用户列表 */
    private List<AdminUserCreateDTO> users;
}
