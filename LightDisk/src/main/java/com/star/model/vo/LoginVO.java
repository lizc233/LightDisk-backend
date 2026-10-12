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
    private String avatar; // 头像访问地址，没有头像时为空串
    private Integer role;
    private Integer isAdmin;
    private Long usedSpace;
    private Long totalSpace;
    private String levelName;
    private LocalDateTime vipExpireTime;
}
