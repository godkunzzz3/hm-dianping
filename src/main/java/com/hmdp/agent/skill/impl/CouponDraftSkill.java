package com.hmdp.agent.skill.impl;

import com.hmdp.agent.MerchantAgentRulePolicyService;
import com.hmdp.agent.skill.AgentActionType;
import com.hmdp.agent.skill.AgentSkillActionPolicyService;
import com.hmdp.agent.skill.AgentSkill;
import com.hmdp.agent.skill.SkillContext;
import com.hmdp.agent.skill.SkillDefinition;
import com.hmdp.agent.skill.SkillResult;
import com.hmdp.agent.skill.SkillRiskLevel;
import com.hmdp.agent.skill.dto.CouponDraftSkillInput;
import com.hmdp.agent.skill.dto.CouponDraftSkillOutput;
import com.hmdp.dto.AgentToolExecutionRequestDTO;
import com.hmdp.dto.AgentToolExecutionResultDTO;
import com.hmdp.dto.MerchantCampaignDraftRequest;
import com.hmdp.dto.MerchantCampaignDraftSkillResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.MerchantCampaignDraftSkillService;
import com.hmdp.tool.AgentToolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠券策略草稿 Skill。
 *
 * <p>该 Skill 只生成待确认活动草稿，真实优惠券创建仍必须走商家确认流程。</p>
 */
@Component
public class CouponDraftSkill implements AgentSkill<CouponDraftSkillInput, CouponDraftSkillOutput> {

    public static final String SKILL_NAME = "coupon_draft_skill";

    private static final String ORDER_ANALYSIS_TOOL = "order_analysis_tool";
    private static final String VOUCHER_ANALYSIS_TOOL = "voucher_analysis_tool";
    private static final List<String> ALLOWED_TOOLS = Collections.unmodifiableList(Arrays.asList(
            ORDER_ANALYSIS_TOOL,
            VOUCHER_ANALYSIS_TOOL
    ));
    private static final List<String> CONFIRM_FIELDS = Collections.unmodifiableList(Arrays.asList(
            "活动标题", "券类型", "支付金额", "抵扣金额", "库存", "开始时间", "结束时间", "适用门店", "是否秒杀券"
    ));

    private final AgentToolExecutor agentToolExecutor;
    private final MerchantCampaignDraftSkillService draftSkillService;
    private final MerchantAgentRulePolicyService rulePolicyService;
    private final AgentSkillActionPolicyService actionPolicyService;

    public CouponDraftSkill(AgentToolExecutor agentToolExecutor,
                            MerchantCampaignDraftSkillService draftSkillService,
                            MerchantAgentRulePolicyService rulePolicyService) {
        this(agentToolExecutor, draftSkillService, rulePolicyService, new AgentSkillActionPolicyService());
    }

    @Autowired
    public CouponDraftSkill(AgentToolExecutor agentToolExecutor,
                            MerchantCampaignDraftSkillService draftSkillService,
                            MerchantAgentRulePolicyService rulePolicyService,
                            AgentSkillActionPolicyService actionPolicyService) {
        this.agentToolExecutor = agentToolExecutor;
        this.draftSkillService = draftSkillService;
        this.rulePolicyService = rulePolicyService;
        this.actionPolicyService = actionPolicyService;
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public SkillDefinition definition() {
        return new SkillDefinition()
                .setSkillName(SKILL_NAME)
                .setDisplayName("优惠券策略草稿 Skill")
                .setDescription("编排订单统计、优惠券分析、风险策略和安全草稿生成入口，仅生成待商家确认的优惠券活动草稿，不直接创建真实优惠券。")
                .setVersion("v1")
                .setAllowedTools(new ArrayList<>(ALLOWED_TOOLS))
                .setRiskLevel(SkillRiskLevel.HIGH)
                .setNeedHumanConfirm(true)
                .setModelCallable(false);
    }

    @Override
    public Class<CouponDraftSkillInput> inputType() {
        return CouponDraftSkillInput.class;
    }

    @Override
    public SkillResult<CouponDraftSkillOutput> execute(CouponDraftSkillInput input, SkillContext context) {
        if (input == null || input.getShopId() == null) {
            return failure("INVALID_SHOP_ID", "shopId不能为空");
        }
        if (isBlank(input.getCampaignGoal())) {
            return failure("INVALID_CAMPAIGN_GOAL", "campaignGoal不能为空");
        }
        String draftType = resolveDraftType(input.getDraftType());
        if (draftType == null) {
            return failure("INVALID_DRAFT_TYPE", "draftType只能是voucher或seckill");
        }
        SkillResult<Void> policyDecision = actionPolicyService.requireAllowed(AgentActionType.CREATE_DRAFT);
        if (!policyDecision.isSuccess()) {
            return failure(policyDecision.getErrorCode(), policyDecision.getErrorMessage());
        }

        String riskText = buildRiskText(input);
        if (hasBypassConfirmIntent(riskText) || rulePolicyService.isProhibitedOperation(riskText)) {
            SkillResult<CouponDraftSkillOutput> result = failure("PROHIBITED_OPERATION", "输入命中高危或绕过人工确认语义");
            result.setRiskLevel(SkillRiskLevel.HIGH);
            result.setNeedHumanConfirm(true);
            result.putMetadata("riskLevel", "HIGH")
                    .putMetadata("needHumanConfirm", true)
                    .putMetadata("riskWarnings", buildRiskWarnings(true, true));
            return result;
        }

        List<String> usedTools = new ArrayList<>();
        Map<String, String> failedTools = new LinkedHashMap<>();
        AgentToolExecutionRequestDTO toolRequest = buildToolRequest(input);
        Map<String, Object> analysisData = new LinkedHashMap<>();
        executeReadonlyAnalysis(ORDER_ANALYSIS_TOOL, toolRequest, usedTools, failedTools, analysisData);
        executeReadonlyAnalysis(VOUCHER_ANALYSIS_TOOL, toolRequest, usedTools, failedTools, analysisData);
        if (!analysisData.containsKey(ORDER_ANALYSIS_TOOL) && !analysisData.containsKey(VOUCHER_ANALYSIS_TOOL)) {
            SkillResult<CouponDraftSkillOutput> result = failure("INSUFFICIENT_EVIDENCE",
                    "订单分析和优惠券分析均不可用，不能生成带金额或库存的可确认草稿");
            result.putMetadata("failedTools", failedTools)
                    .putMetadata("needHumanConfirm", true)
                    .putMetadata("riskLevel", "HIGH");
            return result;
        }

        boolean partialFailure = !failedTools.isEmpty();
        boolean needConfirm = true;
        String policyRiskLevel = rulePolicyService.resolveRiskLevel(riskText, "voucher_campaign_tool");
        String riskLevel = "HIGH";
        CampaignDecision decision = buildCampaignDecision(input, draftType, partialFailure, analysisData);
        MerchantCampaignDraftRequest draftRequest = buildDraftRequest(input, draftType, partialFailure, decision);

        Result draftResult;
        try {
            draftResult = draftSkillService.createDraftFromSkill(input.getShopId(), input.getCampaignGoal(),
                    input.getUserRequirement(), draftRequest, parseSessionId(context));
        } catch (Exception e) {
            return failure("DRAFT_CREATE_FAILED", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (draftResult == null || !Boolean.TRUE.equals(draftResult.getSuccess())) {
            return failure("DRAFT_CREATE_FAILED", draftResult == null ? "草稿生成失败" : draftResult.getErrorMsg());
        }

        MerchantCampaignDraftSkillResultDTO safeResult = (MerchantCampaignDraftSkillResultDTO) draftResult.getData();
        CouponDraftSkillOutput output = buildOutput(input.getShopId(), safeResult, partialFailure, failedTools);
        SkillResult<CouponDraftSkillOutput> result = SkillResult.success(output)
                .setRiskLevel(SkillRiskLevel.HIGH)
                .setNeedHumanConfirm(needConfirm)
                .putMetadata("skillName", SKILL_NAME)
                .putMetadata("shopId", input.getShopId())
                .putMetadata("draftId", output.getDraftId())
                .putMetadata("draftStatus", output.getDraftStatus())
                .putMetadata("riskLevel", riskLevel)
                .putMetadata("policyRiskLevel", policyRiskLevel)
                .putMetadata("needHumanConfirm", true)
                .putMetadata("riskWarnings", output.getRiskWarnings())
                .putMetadata("confirmFields", output.getConfirmFields())
                .putMetadata("usedReadonlyTools", usedTools)
                .putMetadata("campaignDecision", decision.toMap())
                .putMetadata("partialFailure", partialFailure)
                .putMetadata("traceId", context == null ? null : context.getTraceId());
        if (partialFailure) {
            result.putMetadata("failedTools", failedTools);
        }
        for (String usedTool : usedTools) {
            result.addUsedTool(usedTool);
        }
        return result;
    }

    private void executeReadonlyAnalysis(String toolName,
                                         AgentToolExecutionRequestDTO request,
                                         List<String> usedTools,
                                         Map<String, String> failedTools,
                                         Map<String, Object> analysisData) {
        AgentToolExecutionResultDTO result = agentToolExecutor.executeReadonlyTool(toolName, request);
        if (result != null && Boolean.TRUE.equals(result.getSuccess())) {
            usedTools.add(toolName);
            analysisData.put(toolName, result.getData());
            return;
        }
        failedTools.put(toolName, result == null ? "工具执行失败" : result.getErrorMsg());
    }

    private MerchantCampaignDraftRequest buildDraftRequest(CouponDraftSkillInput input,
                                                           String draftType,
                                                           boolean partialFailure,
                                                           CampaignDecision decision) {
        MerchantCampaignDraftRequest request = new MerchantCampaignDraftRequest();
        request.setDraftType(draftType);
        request.setRecommendationType(draftType);
        request.setRecommendationTitle(input.getCampaignGoal().trim());
        request.setRecommendationReason(partialFailure
                ? "部分只读分析工具失败，先生成保守草稿，等待商家确认"
                : "基于只读订单和优惠券分析生成待确认草稿");
        request.setRecommendationAction(firstNotBlank(input.getUserRequirement(), "生成待商家确认的优惠券活动草稿"));
        request.setActualValue(decision.actualValue);
        request.setPayValue(decision.payValue);
        if ("seckill".equals(draftType)) {
            request.setStock(decision.stock);
        }
        return request;
    }

    @SuppressWarnings("unchecked")
    private CampaignDecision buildCampaignDecision(CouponDraftSkillInput input,
                                                   String draftType,
                                                   boolean partialFailure,
                                                   Map<String, Object> analysisData) {
        Map<String, Object> order = toMap(analysisData.get(ORDER_ANALYSIS_TOOL));
        Map<String, Object> voucher = toMap(analysisData.get(VOUCHER_ANALYSIS_TOOL));
        Integer totalOrders = intValue(order.get("totalOrders"));
        Integer paidOrders = intValue(order.get("paidOrders"));
        Long averageOrderValue = longValue(order.get("averageOrderValue"));
        Integer onlineVouchers = intValue(voucher.get("onlineVouchers"));
        Integer currentSeckillStock = intValue(voucher.get("seckillStock"));

        long budget = input.getBudgetLimit() == null || input.getBudgetLimit() <= 0
                ? 20000L
                : input.getBudgetLimit().longValue();
        long baseActual = averageOrderValue == null || averageOrderValue <= 0
                ? Math.min(Math.max(budget / 20, 1000L), 20000L)
                : Math.min(Math.max(Math.round(averageOrderValue * 0.18D), 1000L), 30000L);
        long actualValue = Math.min(baseActual, Math.max(1000L, budget / 2));
        long payValue = Math.max(100L, Math.round(actualValue * ("seckill".equals(draftType) ? 0.55D : 0.75D)));

        int orderBase = paidOrders != null && paidOrders > 0 ? paidOrders : (totalOrders == null ? 10 : totalOrders);
        int budgetCover = actualValue <= 0 ? 1 : (int) Math.max(1L, budget / actualValue);
        int stockBase = Math.max(10, orderBase * ("seckill".equals(draftType) ? 2 : 1));
        int merchantMax = "seckill".equals(draftType) ? 100 : 200;
        int stock = Math.min(Math.min(budgetCover, stockBase), merchantMax);
        if (currentSeckillStock != null && currentSeckillStock > 500) {
            stock = Math.min(stock, 50);
        }

        CampaignDecision decision = new CampaignDecision();
        decision.actualValue = actualValue;
        decision.payValue = payValue;
        decision.stock = Math.max(1, stock);
        decision.evidence.put("totalOrders", totalOrders);
        decision.evidence.put("paidOrders", paidOrders);
        decision.evidence.put("averageOrderValue", averageOrderValue);
        decision.evidence.put("onlineVouchers", onlineVouchers);
        decision.evidence.put("currentSeckillStock", currentSeckillStock);
        decision.evidence.put("budgetLimit", budget);
        decision.evidence.put("partialFailure", partialFailure);
        decision.rules.add("actualValue基于客单价或预算兜底计算，并受预算上限约束");
        decision.rules.add("stock=min(预算可覆盖数量, 历史订单量放大系数, 商家安全上限)");
        if (currentSeckillStock != null && currentSeckillStock > 500) {
            decision.risks.add("当前秒杀库存偏高，本次建议进一步收紧库存");
        }
        if (partialFailure) {
            decision.risks.add("部分分析工具失败，草稿需要商家重点核对");
        }
        return decision;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object data) {
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return new LinkedHashMap<>();
    }

    private CouponDraftSkillOutput buildOutput(Long shopId,
                                               MerchantCampaignDraftSkillResultDTO safeResult,
                                               boolean partialFailure,
                                               Map<String, String> failedTools) {
        CouponDraftSkillOutput output = new CouponDraftSkillOutput()
                .setDraftId(safeResult.getDraftId())
                .setSuggestionId(safeResult.getSuggestionId())
                .setShopId(shopId)
                .setDraftStatus(firstNotBlank(safeResult.getDraftStatus(), "PENDING"))
                .setDraftContent(safeResult.getDraftContent() == null ? new LinkedHashMap<>() : safeResult.getDraftContent())
                .setRiskWarnings(safeResult.getRiskWarnings() == null ? new ArrayList<>() : new ArrayList<>(safeResult.getRiskWarnings()))
                .setConfirmFields(resolveConfirmFields(safeResult.getConfirmFields()))
                .setNeedHumanConfirm(true)
                .setRiskLevel("HIGH")
                .setMetadata(safeResult.getMetadata() == null ? new LinkedHashMap<>() : safeResult.getMetadata());
        if (partialFailure) {
            output.getRiskWarnings().add("部分只读分析工具失败，草稿采用保守默认值，请重点核对金额、库存和时间");
            output.getMetadata().put("partialFailure", true);
            output.getMetadata().put("failedTools", failedTools);
        }
        output.getMetadata().put("needHumanConfirm", true);
        output.getMetadata().put("riskLevel", "HIGH");
        return output;
    }

    private List<String> resolveConfirmFields(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return new ArrayList<>(CONFIRM_FIELDS);
        }
        return new ArrayList<>(fields);
    }

    private AgentToolExecutionRequestDTO buildToolRequest(CouponDraftSkillInput input) {
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime start = end.minusDays(resolveDays(input.getTimeRange()));
        return new AgentToolExecutionRequestDTO()
                .setShopId(input.getShopId())
                .setIntent("voucher_plan")
                .setDateRange(firstNotBlank(input.getTimeRange(), "LAST_30_DAYS"))
                .setStartTime(start)
                .setEndTime(end);
    }

    private int resolveDays(String timeRange) {
        if ("TODAY".equalsIgnoreCase(timeRange)) {
            return 1;
        }
        if ("LAST_7_DAYS".equalsIgnoreCase(timeRange) || "last_7_days".equalsIgnoreCase(timeRange)) {
            return 7;
        }
        return 30;
    }

    private String resolveDraftType(String draftType) {
        if (isBlank(draftType)) {
            return "voucher";
        }
        if ("voucher".equalsIgnoreCase(draftType)) {
            return "voucher";
        }
        if ("seckill".equalsIgnoreCase(draftType)) {
            return "seckill";
        }
        return null;
    }

    private boolean hasBypassConfirmIntent(String text) {
        return containsAny(text, "直接创建", "立即生效", "绕过确认", "自动确认", "不用人工",
                "confirmNow", "autoConfirm", "bypassConfirm");
    }

    private List<String> buildRiskWarnings(boolean prohibited, boolean bypassConfirm) {
        List<String> warnings = new ArrayList<>();
        if (prohibited) {
            warnings.add("输入命中高危操作规则");
        }
        if (bypassConfirm) {
            warnings.add("输入命中绕过人工确认风险");
        }
        warnings.add("优惠券活动草稿必须由商家确认后才会创建真实优惠券");
        return warnings;
    }

    private SkillResult<CouponDraftSkillOutput> failure(String errorCode, String errorMessage) {
        return SkillResult.<CouponDraftSkillOutput>failure(errorCode, errorMessage)
                .setRiskLevel(SkillRiskLevel.HIGH)
                .setNeedHumanConfirm(true);
    }

    private Long parseSessionId(SkillContext context) {
        if (context == null || isBlank(context.getSessionId())) {
            return null;
        }
        try {
            return Long.valueOf(context.getSessionId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String buildRiskText(CouponDraftSkillInput input) {
        return input.getCampaignGoal() + " " + (input.getUserRequirement() == null ? "" : input.getUserRequirement());
    }

    private boolean containsAny(String text, String... values) {
        if (text == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String firstNotBlank(String first, String fallback) {
        return isBlank(first) ? fallback : first.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty() || "null".equals(value);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static class CampaignDecision {
        private Long actualValue;
        private Long payValue;
        private Integer stock;
        private final Map<String, Object> evidence = new LinkedHashMap<>();
        private final List<String> rules = new ArrayList<>();
        private final List<String> risks = new ArrayList<>();

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("actualValue", actualValue);
            map.put("payValue", payValue);
            map.put("stock", stock);
            map.put("evidence", evidence);
            map.put("rules", rules);
            map.put("risks", risks);
            return map;
        }
    }
}
