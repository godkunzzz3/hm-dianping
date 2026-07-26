package com.hmdp.agent.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.skill.AgentSkill;
import com.hmdp.agent.skill.SkillContext;
import com.hmdp.agent.skill.SkillDefinition;
import com.hmdp.agent.skill.SkillResult;
import com.hmdp.agent.skill.SkillRiskLevel;
import com.hmdp.agent.skill.dto.MerchantDiagnosisSkillInput;
import com.hmdp.agent.skill.dto.MerchantDiagnosisSkillOutput;
import com.hmdp.dto.AgentToolDefinitionDTO;
import com.hmdp.dto.AgentToolExecutionRequestDTO;
import com.hmdp.dto.AgentToolExecutionResultDTO;
import com.hmdp.tool.AgentToolExecutor;
import com.hmdp.tool.AgentToolRegistry;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家运营诊断 Skill。
 *
 * <p>该 Skill 编排多个只读原子 Tool，不调用 operation_diagnosis_tool，也不触发写库或草稿确认流程。</p>
 */
@Component
public class MerchantDiagnosisSkill implements AgentSkill<MerchantDiagnosisSkillInput, MerchantDiagnosisSkillOutput> {

    public static final String SKILL_NAME = "merchant_diagnosis_skill";

    private static final String SHOP_PROFILE_TOOL = "shop_profile_tool";
    private static final String ORDER_ANALYSIS_TOOL = "order_analysis_tool";
    private static final String VOUCHER_ANALYSIS_TOOL = "voucher_analysis_tool";
    private static final String REVIEW_CONTENT_TOOL = "review_content_tool";

    private static final List<String> ALLOWED_TOOLS = Collections.unmodifiableList(Arrays.asList(
            SHOP_PROFILE_TOOL,
            ORDER_ANALYSIS_TOOL,
            VOUCHER_ANALYSIS_TOOL,
            REVIEW_CONTENT_TOOL
    ));

    private final AgentToolExecutor agentToolExecutor;
    private final AgentToolRegistry agentToolRegistry;
    private final ObjectMapper objectMapper;

    public MerchantDiagnosisSkill(AgentToolExecutor agentToolExecutor,
                                  AgentToolRegistry agentToolRegistry,
                                  ObjectMapper objectMapper) {
        this.agentToolExecutor = agentToolExecutor;
        this.agentToolRegistry = agentToolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return SKILL_NAME;
    }

    @Override
    public SkillDefinition definition() {
        return new SkillDefinition()
                .setSkillName(SKILL_NAME)
                .setDisplayName("商家运营诊断 Skill")
                .setDescription("编排商家画像、订单统计、优惠券分析和评价摘要等只读工具，生成结构化商家运营诊断结果。")
                .setVersion("v1")
                .setAllowedTools(new ArrayList<>(ALLOWED_TOOLS))
                .setRiskLevel(SkillRiskLevel.LOW)
                .setNeedHumanConfirm(false)
                .setModelCallable(false);
    }

    @Override
    public Class<MerchantDiagnosisSkillInput> inputType() {
        return MerchantDiagnosisSkillInput.class;
    }

    @Override
    public SkillResult<MerchantDiagnosisSkillOutput> execute(MerchantDiagnosisSkillInput input, SkillContext context) {
        if (input == null || input.getShopId() == null) {
            return SkillResult.failure("INVALID_SHOP_ID", "shopId不能为空");
        }
        SkillResult<Void> boundaryCheck = validateReadonlyToolBoundary();
        if (!boundaryCheck.isSuccess()) {
            return SkillResult.failure(boundaryCheck.getErrorCode(), boundaryCheck.getErrorMessage());
        }

        String timeRange = normalizeTimeRange(input.getTimeRange());
        AgentToolExecutionRequestDTO request = buildToolRequest(input.getShopId(), timeRange);
        MerchantDiagnosisSkillOutput output = new MerchantDiagnosisSkillOutput()
                .setShopId(input.getShopId())
                .setTimeRange(timeRange);
        List<String> usedTools = new ArrayList<>();
        Map<String, String> partialFailures = new LinkedHashMap<>();

        AgentToolExecutionResultDTO shopProfile = executeTool(SHOP_PROFILE_TOOL, request);
        if (!Boolean.TRUE.equals(shopProfile.getSuccess())) {
            return buildFailure("SHOP_PROFILE_FAILED", shopProfile, usedTools);
        }
        output.setShopProfile(toMap(shopProfile.getData()));
        usedTools.add(SHOP_PROFILE_TOOL);

        AgentToolExecutionResultDTO orderStats = executeTool(ORDER_ANALYSIS_TOOL, request);
        if (!Boolean.TRUE.equals(orderStats.getSuccess())) {
            return buildFailure("ORDER_STATS_FAILED", orderStats, usedTools);
        }
        output.setOrderStats(toMap(orderStats.getData()));
        usedTools.add(ORDER_ANALYSIS_TOOL);

        AgentToolExecutionResultDTO voucherStats = executeTool(VOUCHER_ANALYSIS_TOOL, request);
        if (Boolean.TRUE.equals(voucherStats.getSuccess())) {
            output.setVoucherStats(toMap(voucherStats.getData()));
            usedTools.add(VOUCHER_ANALYSIS_TOOL);
        } else {
            output.setVoucherStats(Collections.<String, Object>emptyMap());
            partialFailures.put(VOUCHER_ANALYSIS_TOOL, safeError(voucherStats));
        }

        AgentToolExecutionResultDTO reviewSummary = executeTool(REVIEW_CONTENT_TOOL, request);
        if (Boolean.TRUE.equals(reviewSummary.getSuccess())) {
            output.setReviewSummary(toMap(reviewSummary.getData()));
            usedTools.add(REVIEW_CONTENT_TOOL);
        } else {
            output.setReviewSummary(Collections.<String, Object>emptyMap());
            partialFailures.put(REVIEW_CONTENT_TOOL, safeError(reviewSummary));
        }

        output.setUsedTools(new ArrayList<>(usedTools));
        double confidence = fillDeterministicDiagnosis(output, partialFailures, usedTools);

        SkillResult<MerchantDiagnosisSkillOutput> result = SkillResult.success(output)
                .setRiskLevel(SkillRiskLevel.LOW)
                .setNeedHumanConfirm(false)
                .setConfidence(confidence)
                .putMetadata("skillName", SKILL_NAME)
                .putMetadata("timeRange", timeRange)
                .putMetadata("traceId", context == null ? null : context.getTraceId())
                .putMetadata("partialFailure", !partialFailures.isEmpty())
                .putMetadata("confidenceFactors", output.getConfidenceFactors());
        if (!partialFailures.isEmpty()) {
            result.putMetadata("partialFailures", partialFailures);
        }
        for (String usedTool : usedTools) {
            result.addUsedTool(usedTool);
        }
        return result;
    }

    private SkillResult<Void> validateReadonlyToolBoundary() {
        List<AgentToolDefinitionDTO> definitions = agentToolRegistry.listDefinitions();
        for (String allowedTool : ALLOWED_TOOLS) {
            AgentToolDefinitionDTO definition = findDefinition(definitions, allowedTool);
            if (definition == null) {
                return SkillResult.failure("TOOL_DEFINITION_MISSING", "工具定义不存在：" + allowedTool);
            }
            if (!"readonly".equals(definition.getToolType())
                    || !"read".equals(definition.getAccessLevel())
                    || Boolean.TRUE.equals(definition.getWriteDatabase())
                    || Boolean.TRUE.equals(definition.getRequireMerchantConfirm())) {
                return SkillResult.failure("TOOL_BOUNDARY_INVALID", "Skill 只能编排只读工具：" + allowedTool);
            }
        }
        return SkillResult.success(null);
    }

    private AgentToolDefinitionDTO findDefinition(List<AgentToolDefinitionDTO> definitions, String toolName) {
        if (definitions == null) {
            return null;
        }
        for (AgentToolDefinitionDTO definition : definitions) {
            if (definition != null && toolName.equals(definition.getName())) {
                return definition;
            }
        }
        return null;
    }

    private AgentToolExecutionRequestDTO buildToolRequest(Long shopId, String timeRange) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(resolveDays(timeRange));
        return new AgentToolExecutionRequestDTO()
                .setShopId(shopId)
                .setIntent("operation_chat")
                .setDateRange(timeRange)
                .setStartTime(startTime)
                .setEndTime(endTime);
    }

    private AgentToolExecutionResultDTO executeTool(String toolName, AgentToolExecutionRequestDTO request) {
        try {
            return agentToolExecutor.executeReadonlyTool(toolName, request);
        } catch (Exception e) {
            return new AgentToolExecutionResultDTO()
                    .setToolName(toolName)
                    .setSuccess(false)
                    .setErrorMsg(e.getMessage());
        }
    }

    private SkillResult<MerchantDiagnosisSkillOutput> buildFailure(String errorCode,
                                                                   AgentToolExecutionResultDTO failedTool,
                                                                   List<String> usedTools) {
        SkillResult<MerchantDiagnosisSkillOutput> result = SkillResult.<MerchantDiagnosisSkillOutput>failure(errorCode, safeError(failedTool))
                .setRiskLevel(SkillRiskLevel.LOW)
                .setNeedHumanConfirm(false)
                .putMetadata("skillName", SKILL_NAME)
                .putMetadata("failedTool", failedTool == null ? null : failedTool.getToolName());
        for (String usedTool : usedTools) {
            result.addUsedTool(usedTool);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object data) {
        if (data == null) {
            return new LinkedHashMap<>();
        }
        if (data instanceof Map) {
            return new LinkedHashMap<>((Map<String, Object>) data);
        }
        return objectMapper.convertValue(data, Map.class);
    }

    private double fillDeterministicDiagnosis(MerchantDiagnosisSkillOutput output,
                                              Map<String, String> partialFailures,
                                              List<String> usedTools) {
        List<Map<String, Object>> findings = buildFindings(output, partialFailures);
        List<String> keyFindings = new ArrayList<>();
        for (Map<String, Object> finding : findings) {
            keyFindings.add(String.valueOf(finding.get("finding")));
        }
        if (!partialFailures.isEmpty()) {
            keyFindings.add("部分非核心数据读取失败，本次诊断结果应按已成功返回的数据保守解读。");
        }

        List<String> possibleReasons = new ArrayList<>();
        for (Map<String, Object> finding : findings) {
            Object causes = finding.get("possibleCauses");
            if (causes instanceof List) {
                for (Object cause : (List<?>) causes) {
                    possibleReasons.add(String.valueOf(cause));
                }
            }
        }
        if (possibleReasons.isEmpty()) {
            possibleReasons.add("当前规则未发现强异常信号，建议继续结合订单、优惠券和内容互动趋势观察。");
        }
        if (!partialFailures.isEmpty()) {
            possibleReasons.add("非核心工具失败可能导致优惠券或评价维度信息不完整。");
        }

        List<String> suggestions = new ArrayList<>();
        for (Map<String, Object> finding : findings) {
            Object suggestion = finding.get("suggestion");
            if (suggestion != null) {
                suggestions.add(String.valueOf(suggestion));
            }
        }
        if (suggestions.isEmpty()) {
            suggestions.add("当前未触发强规则建议，可以先保持活动节奏并继续积累样本。");
        }
        suggestions.add("如需生成活动方案，应进入独立草稿 Skill，并继续走商家确认流程。");

        Map<String, Object> confidenceFactors = buildConfidenceFactors(output, partialFailures, usedTools);
        double confidence = numberAsDouble(confidenceFactors.get("finalConfidence"), 0.6D);
        output.setKeyFindings(keyFindings)
                .setFindings(findings)
                .setPossibleReasons(possibleReasons)
                .setSuggestions(suggestions)
                .setConfidenceFactors(confidenceFactors);
        return confidence;
    }

    private List<Map<String, Object>> buildFindings(MerchantDiagnosisSkillOutput output,
                                                    Map<String, String> partialFailures) {
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> order = output.getOrderStats();
        Map<String, Object> voucher = output.getVoucherStats();
        Map<String, Object> review = output.getReviewSummary();

        Integer totalOrders = intValue(order.get("totalOrders"));
        Integer paidOrders = intValue(order.get("paidOrders"));
        Integer refundedOrders = intValue(order.get("refundedOrders"));
        Long estimatedRevenue = longValue(order.get("estimatedRevenue"));
        Long averageOrderValue = longValue(order.get("averageOrderValue"));
        Double paymentRate = ratio(paidOrders, totalOrders);
        Double refundRate = ratio(refundedOrders, totalOrders);

        Integer totalVouchers = intValue(voucher.get("totalVouchers"));
        Integer onlineVouchers = intValue(voucher.get("onlineVouchers"));
        Integer seckillStock = intValue(voucher.get("seckillStock"));
        Boolean hasSeckill = boolValue(voucher.get("hasSeckill"));

        Integer blogCount = intValue(review.get("blogCount"));
        Integer likedCount = intValue(review.get("likedCount"));
        Integer commentCount = intValue(review.get("commentCount"));
        String engagementLevel = stringValue(review.get("engagementLevel"));

        if (totalOrders != null && totalOrders == 0) {
            findings.add(finding("F_ORDER_SAMPLE_EMPTY", "订单样本不足，暂不能形成强经营判断", "MEDIUM",
                    evidence("totalOrders", totalOrders, "threshold", 1),
                    Arrays.asList("近期活动曝光不足", "优惠券供给或内容引流不足"),
                    "先补齐活动曝光和内容入口，再观察订单样本变化"));
        } else if (paymentRate != null && paymentRate < 0.30D) {
            findings.add(finding("F_PAYMENT_RATE_LOW", "支付转化率偏低", "HIGH",
                    evidence("paidOrders", paidOrders, "totalOrders", totalOrders, "paymentRate", round(paymentRate), "threshold", 0.30D),
                    Arrays.asList("券门槛偏高", "用户下单后犹豫或支付链路吸引力不足"),
                    "优先检查券门槛、活动标题和支付前权益说明"));
        } else if (paymentRate != null) {
            findings.add(finding("F_PAYMENT_RATE_OBSERVED", "已读取支付转化指标", "LOW",
                    evidence("paidOrders", paidOrders, "totalOrders", totalOrders, "paymentRate", round(paymentRate)),
                    Collections.singletonList("当前支付转化未触发低转化规则"),
                    "继续观察支付转化与券使用趋势"));
        }

        if (refundRate != null && refundRate > 0.10D) {
            findings.add(finding("F_REFUND_RATE_HIGH", "退款占比偏高", "HIGH",
                    evidence("refundedOrders", refundedOrders, "totalOrders", totalOrders, "refundRate", round(refundRate), "threshold", 0.10D),
                    Arrays.asList("商品或服务预期不一致", "券规则说明不清晰"),
                    "复核近期退款原因，并在活动规则中提前说明限制条件"));
        }

        if (onlineVouchers != null && onlineVouchers == 0) {
            findings.add(finding("F_NO_ONLINE_VOUCHER", "当前缺少在线优惠券供给", "MEDIUM",
                    evidence("onlineVouchers", onlineVouchers, "totalVouchers", totalVouchers),
                    Arrays.asList("活动入口不足", "用户缺少转化激励"),
                    "补充小库存优惠券或秒杀券，先用低风险库存验证转化"));
        } else if (Boolean.FALSE.equals(hasSeckill)) {
            findings.add(finding("F_NO_SECKILL", "当前未配置秒杀券", "LOW",
                    evidence("hasSeckill", false, "onlineVouchers", onlineVouchers),
                    Collections.singletonList("缺少短期刺激型活动"),
                    "如近期转化偏弱，可考虑小库存秒杀券做曝光测试"));
        } else if (seckillStock != null && seckillStock > 500) {
            findings.add(finding("F_SECKILL_STOCK_HIGH", "秒杀库存偏高", "MEDIUM",
                    evidence("seckillStock", seckillStock, "threshold", 500),
                    Arrays.asList("库存释放过大", "活动成本风险偏高"),
                    "降低单次秒杀库存，分批观察核销和支付转化"));
        }

        if (blogCount != null && blogCount == 0) {
            findings.add(finding("F_CONTENT_EMPTY", "探店内容样本不足", "MEDIUM",
                    evidence("blogCount", blogCount, "commentCount", commentCount, "likedCount", likedCount),
                    Arrays.asList("内容曝光不足", "用户决策前缺少真实体验参考"),
                    "优先补充探店内容或引导用户发布体验笔记"));
        } else if ("低".equals(engagementLevel)) {
            findings.add(finding("F_ENGAGEMENT_LOW", "内容互动等级偏低", "MEDIUM",
                    evidence("blogCount", blogCount, "commentCount", commentCount, "likedCount", likedCount, "engagementLevel", engagementLevel),
                    Arrays.asList("内容吸引力不足", "用户互动入口不明显"),
                    "优化探店标题和活动关联，引导评论互动"));
        }

        if (findings.isEmpty()) {
            findings.add(finding("F_BASELINE_READY", "已完成订单、优惠券和评价基础诊断", "LOW",
                    evidence("estimatedRevenue", estimatedRevenue, "averageOrderValue", averageOrderValue,
                            "onlineVouchers", onlineVouchers, "blogCount", blogCount),
                    Collections.singletonList("当前数据未触发高风险经营规则"),
                    "保持当前节奏，并持续观察活动转化和内容互动"));
        }
        return findings;
    }

    private Map<String, Object> finding(String id, String finding, String severity, Map<String, Object> evidence,
                                        List<String> possibleCauses, String suggestion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("finding", finding);
        row.put("severity", severity);
        row.put("evidence", evidence);
        row.put("possibleCauses", possibleCauses);
        row.put("suggestion", suggestion);
        return row;
    }

    private Map<String, Object> evidence(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

    private Map<String, Object> buildConfidenceFactors(MerchantDiagnosisSkillOutput output,
                                                       Map<String, String> partialFailures,
                                                       List<String> usedTools) {
        int successCount = usedTools == null ? 0 : usedTools.size();
        double toolSuccessRate = successCount / 4.0D;
        int dataSections = 0;
        if (output.getShopProfile() != null && !output.getShopProfile().isEmpty()) {
            dataSections++;
        }
        if (output.getOrderStats() != null && !output.getOrderStats().isEmpty()) {
            dataSections++;
        }
        if (output.getVoucherStats() != null && !output.getVoucherStats().isEmpty()) {
            dataSections++;
        }
        if (output.getReviewSummary() != null && !output.getReviewSummary().isEmpty()) {
            dataSections++;
        }
        double dataCoverage = dataSections / 4.0D;
        Integer totalOrders = intValue(output.getOrderStats().get("totalOrders"));
        double sampleReliability = totalOrders == null || totalOrders <= 0
                ? 0.55D
                : Math.min(1.0D, 0.60D + Math.min(totalOrders, 50) / 125.0D);
        double ruleStrength = hasHighSeverity(output.getFindings()) ? 0.90D : 0.75D;
        double timeWindowCompleteness = partialFailures == null || partialFailures.isEmpty() ? 1.0D : 0.85D;
        double finalConfidence = toolSuccessRate * dataCoverage * sampleReliability * ruleStrength * timeWindowCompleteness;

        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("toolSuccessRate", round(toolSuccessRate));
        factors.put("dataCoverage", round(dataCoverage));
        factors.put("sampleReliability", round(sampleReliability));
        factors.put("ruleStrength", round(ruleStrength));
        factors.put("timeWindowCompleteness", round(timeWindowCompleteness));
        factors.put("finalConfidence", round(Math.max(0.1D, Math.min(1.0D, finalConfidence))));
        return factors;
    }

    private boolean hasHighSeverity(List<Map<String, Object>> findings) {
        if (findings == null) {
            return false;
        }
        for (Map<String, Object> finding : findings) {
            if ("HIGH".equals(finding.get("severity"))) {
                return true;
            }
        }
        return false;
    }

    private Double ratio(Integer numerator, Integer denominator) {
        if (numerator == null || denominator == null || denominator <= 0) {
            return null;
        }
        return numerator.doubleValue() / denominator.doubleValue();
    }

    private Double round(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 10000D) / 10000D;
    }

    private double numberAsDouble(Object value, double fallback) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    private Boolean boolValue(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return null;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeTimeRange(String timeRange) {
        if (timeRange == null || timeRange.trim().isEmpty()) {
            return "last_7_days";
        }
        return timeRange.trim();
    }

    private long resolveDays(String timeRange) {
        if ("last_30_days".equalsIgnoreCase(timeRange) || "LAST_30_DAYS".equalsIgnoreCase(timeRange)) {
            return 30L;
        }
        if ("today".equalsIgnoreCase(timeRange) || "TODAY".equalsIgnoreCase(timeRange)) {
            return 1L;
        }
        return 7L;
    }

    private String safeError(AgentToolExecutionResultDTO result) {
        if (result == null || result.getErrorMsg() == null || result.getErrorMsg().trim().isEmpty()) {
            return "工具执行失败";
        }
        return result.getErrorMsg();
    }
}
