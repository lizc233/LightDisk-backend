package com.star.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LoginVO {
    private Long id;
    private String username;
    private String token; // 最终生成的 JWT
    private String email;
    private Integer role;
    private Long usedSpace;
    private Long totalSpace;
    private String levelName;
    private LocalDateTime vipExpireTime;
}
