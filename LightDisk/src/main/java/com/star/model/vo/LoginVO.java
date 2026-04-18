package com.star.model.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginVO {
    private Long id;
    private String username;
    private String token; // 最终生成的 JWT
}
