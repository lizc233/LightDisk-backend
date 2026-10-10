package com.star.model.dto;

import lombok.Data;

@Data
public class AiChatRequestDTO {
    private String query;
    private Integer topK;
}
