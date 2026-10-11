package com.star.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user_settings")
public class UserSettings {

    @TableId
    private Long userId;

    private Integer defaultShareExpireDays;

    private Integer recycleReminderEnabled;

    private Integer recycleReminderDays;

    private String imageOpenAction;

    private String videoOpenAction;

    private String docOpenAction;

    private String otherOpenAction;

    /** 接收私信策略：0-所有人 1-仅联系人 2-不接受 */
    private Integer messagePolicy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
