package com.star.model.vo;

import lombok.Data;

import java.util.List;

@Data
public class AiChatResponseVO {
    private String mode;
    private String intent;
    private String answer;
    private String traceSummary;
    private List<AiStageVO> stages;
    private List<AiSourceVO> sources;
    private List<AiCapabilityVO> capabilityCards;
    private List<String> followUpQuestions;
}
