package com.hmdp.agent.skill.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家运营诊断 Skill 输出。
 */
@Data
@Accessors(chain = true)
public class MerchantDiagnosisSkillOutput {

    private Long shopId;

    private String timeRange;

    private Map<String, Object> shopProfile = new LinkedHashMap<>();

    private Map<String, Object> orderStats = new LinkedHashMap<>();

    private Map<String, Object> voucherStats = new LinkedHashMap<>();

    private Map<String, Object> reviewSummary = new LinkedHashMap<>();

    private List<String> keyFindings = new ArrayList<>();

    /**
     * 规则诊断明细。每条 finding 必须带 evidence，避免只返回固定文案。
     */
    private List<Map<String, Object>> findings = new ArrayList<>();

    private List<String> possibleReasons = new ArrayList<>();

    private List<String> suggestions = new ArrayList<>();

    private List<String> usedTools = new ArrayList<>();

    /**
     * 置信度计算拆解，例如工具成功率、样本量可靠性、数据覆盖度。
     */
    private Map<String, Object> confidenceFactors = new LinkedHashMap<>();
}
