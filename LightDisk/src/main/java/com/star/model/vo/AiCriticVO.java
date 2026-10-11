package com.star.model.vo;

import lombok.Data;

import java.util.List;

/**
 * 复核 Agent（Critic）结论
 */
@Data
public class AiCriticVO {

    /** 是否通过复核 */
    private Boolean passed;

    /** 复核方式：llm（模型复核）/ rule（规则复核） */
    private String mode;

    /** 复核模型名（llm 时有值） */
    private String reviewer;

    /** 发现的问题列表 */
    private List<String> issues;

    /** 是否触发了修正重写 */
    private Boolean repaired = false;
}
