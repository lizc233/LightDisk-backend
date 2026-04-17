package com.star.model.vo;


import lombok.Data;

@Data
public class ShareInfoVO {
    private String shareUrl;     // 分享的完整链接
    private String extractCode;  // 4位提取码
}
