package com.star.model.vo;

import lombok.Data;

import java.util.List;

@Data
public class AiChatResponseVO {
    /** agent=模型+工具链路，offline=内置规则引擎（离线降级） */
    private String mode;
    private String intent;
    private String answer;
    private String traceSummary;
    private List<AiStageVO> stages;
    private List<AiSourceVO> sources;
    private List<AiCapabilityVO> capabilityCards;
    private List<String> followUpQuestions;

    // ===== Agent 增强字段 =====
    /** 实际使用的模型名（离线为 null） */
    private String agentModel;
    /** 当前用户生效 VIP 等级与名称 */
    private Integer userLevel;
    private String userLevelName;
    /** 复核结论 */
    private AiCriticVO critic;
}
