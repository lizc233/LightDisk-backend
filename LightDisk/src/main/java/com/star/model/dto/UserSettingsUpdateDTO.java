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
}
