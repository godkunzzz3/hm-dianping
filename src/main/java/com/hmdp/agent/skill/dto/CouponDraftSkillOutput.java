package com.hmdp.agent.skill.dto;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠券策略草稿 Skill 输出。
 */
@Data
@Accessors(chain = true)
public class CouponDraftSkillOutput {

    private Long draftId;

    private Long suggestionId;

    private Long shopId;

    private String draftStatus;

    private Map<String, Object> draftContent = new LinkedHashMap<>();

    private List<String> riskWarnings = new ArrayList<>();

    private List<String> confirmFields = new ArrayList<>();

    private Boolean needHumanConfirm = true;

    private String riskLevel = "HIGH";

    private Map<String, Object> metadata = new LinkedHashMap<>();
}
