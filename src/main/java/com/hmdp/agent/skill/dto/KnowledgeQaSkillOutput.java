package com.hmdp.agent.skill.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家知识问答 Skill 输出。
 */
@Data
@Accessors(chain = true)
public class KnowledgeQaSkillOutput {

    private Long shopId;

    private String intent;

    private String question;

    private String answer;

    private List<Object> retrievedChunks = new ArrayList<>();

    private Double confidence;

    /**
     * 仅表示归一化后的保守置信度，不直接混用向量分数、关键词分数或重排分数。
     */
    private Double calibratedConfidence;

    private Double bestVectorScore;

    private Double bestKeywordScore;

    private Double bestRerankScore;

    private Boolean noReliableHit = true;

    private Integer topK;

    private Map<String, Object> metadata = new LinkedHashMap<>();
}
