package com.star.model.dto;

import lombok.Data;

@Data
public class AiChatRequestDTO {
    private String query;
    private Integer topK;
    /** 前端文件选择器点选的文件（可选）：指定后直接以该文件为分析对象，不再靠关键词猜 */
    private Long fileId;
}
