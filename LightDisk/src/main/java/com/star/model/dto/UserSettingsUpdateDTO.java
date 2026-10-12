package com.star.model.dto;

import lombok.Data;

@Data
public class UserSettingsUpdateDTO {

    private Integer defaultShareExpireDays;

    private Integer recycleReminderEnabled;

    private Integer recycleReminderDays;

    private String imageOpenAction;

    private String videoOpenAction;

    private String docOpenAction;

    private String otherOpenAction;

    /** 接收私信策略：0-所有人 1-仅联系人 2-不接受 */
    private Integer messagePolicy;

    /** 快速筛选区条目顺序 JSON，最长 2000 字符 */
    private String sidebarOrder;
}
