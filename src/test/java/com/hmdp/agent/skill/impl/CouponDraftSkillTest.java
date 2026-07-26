package com.hmdp.agent.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.MerchantAgentRulePolicyService;
import com.hmdp.agent.skill.SkillContext;
import com.hmdp.agent.skill.SkillDefinition;
import com.hmdp.agent.skill.SkillExecutionService;
import com.hmdp.agent.skill.SkillRegistry;
import com.hmdp.agent.skill.SkillResult;
import com.hmdp.agent.skill.SkillRiskLevel;
import com.hmdp.agent.skill.dto.CouponDraftSkillInput;
import com.hmdp.agent.skill.dto.CouponDraftSkillOutput;
import com.hmdp.dto.AgentToolExecutionRequestDTO;
import com.hmdp.dto.AgentToolExecutionResultDTO;
import com.hmdp.dto.MerchantCampaignDraftRequest;
import com.hmdp.dto.MerchantCampaignDraftSkillResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.AgentCampaignDraft;
import com.hmdp.service.MerchantCampaignDraftSkillService;
import com.hmdp.tool.AgentToolExecutor;
import com.hmdp.tool.VoucherAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponDraftSkillTest {

    private static final Long SHOP_ID = 10143L;
    private static final Long DRAFT_ID = 9002L;

    @Mock
    private AgentToolExecutor agentToolExecutor;
    @Mock
    private MerchantCampaignDraftSkillService draftSkillService;
    @Mock
    private MerchantAgentRulePolicyService rulePolicyService;
    @Mock
    private VoucherAgentTool voucherAgentTool;

    private CouponDraftSkill skill;

    @BeforeEach
    void setUp() {
        skill = new CouponDraftSkill(agentToolExecutor, draftSkillService, rulePolicyService);
        lenient().when(rulePolicyService.resolveRiskLevel(anyString(), eq("voucher_campaign_tool"))).thenReturn("medium");
    }

    @Test
    void shouldExposeHighRiskDraftDefinition() {
        SkillDefinition definition = skill.definition();

        assertEquals("coupon_draft_skill", definition.getSkillName());
        assertEquals(Arrays.asList("order_analysis_tool", "voucher_analysis_tool"), definition.getAllowedTools());
        assertFalse(definition.getAllowedTools().contains("voucher_campaign_tool"));
        assertFalse(definition.getAllowedTools().contains("operation_diagnosis_tool"));
        assertEquals(SkillRiskLevel.HIGH, definition.getRiskLevel());
        assertTrue(definition.getNeedHumanConfirm());
        assertFalse(definition.getModelCallable());
    }

    @Test
    void shouldCreateDraftThroughSafeServiceOnly() {
        mockReadonlyToolsSuccess();
        when(rulePolicyService.isProhibitedOperation(anyString())).thenReturn(false);
        when(draftSkillService.createDraftFromSkill(eq(SHOP_ID), eq("设计周末复购券"), eq("预算控制在100元"),
                any(MerchantCampaignDraftRequest.class), eq(123L))).thenReturn(Result.ok(safeDraftResult()));

        SkillResult<CouponDraftSkillOutput> result = skill.execute(new CouponDraftSkillInput()
                .setShopId(SHOP_ID)
                .setCampaignGoal("设计周末复购券")
                .setUserRequirement("预算控制在100元")
                .setDraftType("voucher")
                .setBudgetLimit(10000), new SkillContext().setSessionId("123").setTraceId("trace-coupon"));

        assertTrue(result.isSuccess());
        assertEquals(SkillRiskLevel.HIGH, result.getRiskLevel());
        assertTrue(result.isNeedHumanConfirm());
        assertEquals(DRAFT_ID, result.getOutput().getDraftId());
        assertEquals(Boolean.TRUE, result.getOutput().getNeedHumanConfirm());
        assertEquals("HIGH", result.getOutput().getRiskLevel());
        verify(draftSkillService).createDraftFromSkill(eq(SHOP_ID), eq("设计周末复购券"), eq("预算控制在100元"),
                any(MerchantCampaignDraftRequest.class), eq(123L));
        verify(voucherAgentTool, never()).createVoucherFromDraft(any(AgentCampaignDraft.class));
    }

    @Test
    void shouldUseAnalysisEvidenceToDecideDraftParameters() {
        Map<String, Object> orderData = new HashMap<>();
        orderData.put("totalOrders", 20);
        orderData.put("paidOrders", 8);
        orderData.put("averageOrderValue", 12000L);
        Map<String, Object> voucherData = new HashMap<>();
        voucherData.put("onlineVouchers", 1);
        voucherData.put("seckillStock", 40);
        when(agentToolExecutor.executeReadonlyTool(eq("order_analysis_tool"), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(new AgentToolExecutionResultDTO()
                        .setToolName("order_analysis_tool")
                        .setSuccess(true)
                        .setData(orderData));
        when(agentToolExecutor.executeReadonlyTool(eq("voucher_analysis_tool"), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(new AgentToolExecutionResultDTO()
                        .setToolName("voucher_analysis_tool")
                        .setSuccess(true)
                        .setData(voucherData));
        when(rulePolicyService.isProhibitedOperation(anyString())).thenReturn(false);
        when(draftSkillService.createDraftFromSkill(eq(SHOP_ID), eq("设计周末秒杀券"), eq("预算控制在240元"),
                any(MerchantCampaignDraftRequest.class), any())).thenReturn(Result.ok(safeDraftResult()));

        SkillResult<CouponDraftSkillOutput> result = skill.execute(new CouponDraftSkillInput()
                .setShopId(SHOP_ID)
                .setCampaignGoal("设计周末秒杀券")
                .setUserRequirement("预算控制在240元")
                .setDraftType("seckill")
                .setBudgetLimit(24000), new SkillContext());

        assertTrue(result.isSuccess());
        ArgumentCaptor<MerchantCampaignDraftRequest> captor = forClass(MerchantCampaignDraftRequest.class);
        verify(draftSkillService).createDraftFromSkill(eq(SHOP_ID), eq("设计周末秒杀券"), eq("预算控制在240元"),
                captor.capture(), any());
        assertEquals(2160L, captor.getValue().getActualValue());
        assertEquals(1188L, captor.getValue().getPayValue());
        assertEquals(11, captor.getValue().getStock());
        assertNotNull(result.getMetadata().get("campaignDecision"));
    }

    @Test
    void shouldCallOnlyReadonlyAnalysisTools() {
        mockReadonlyToolsSuccess();
        when(rulePolicyService.isProhibitedOperation(anyString())).thenReturn(false);
        when(draftSkillService.createDraftFromSkill(anyLong(), anyString(), anyString(),
                any(MerchantCampaignDraftRequest.class), any())).thenReturn(Result.ok(safeDraftResult()));

        SkillResult<CouponDraftSkillOutput> result = skill.execute(validInput(), new SkillContext());

        assertTrue(result.isSuccess());
        assertTrue(result.getUsedTools().contains("order_analysis_tool"));
        assertTrue(result.getUsedTools().contains("voucher_analysis_tool"));
        verify(agentToolExecutor).executeReadonlyTool(eq("order_analysis_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor).executeReadonlyTool(eq("voucher_analysis_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor, never()).executeReadonlyTool(eq("voucher_campaign_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor, never()).executeReadonlyTool(eq("operation_diagnosis_tool"), any(AgentToolExecutionRequestDTO.class));
    }

    @Test
    void shouldRejectBlankShopId() {
        SkillResult<CouponDraftSkillOutput> result = skill.execute(new CouponDraftSkillInput()
                .setCampaignGoal("设计活动"), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("INVALID_SHOP_ID", result.getErrorCode());
        verify(draftSkillService, never()).createDraftFromSkill(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void shouldRejectBlankCampaignGoal() {
        SkillResult<CouponDraftSkillOutput> result = skill.execute(new CouponDraftSkillInput()
                .setShopId(SHOP_ID)
                .setCampaignGoal("  "), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("INVALID_CAMPAIGN_GOAL", result.getErrorCode());
        verify(draftSkillService, never()).createDraftFromSkill(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void shouldRejectInvalidDraftType() {
        SkillResult<CouponDraftSkillOutput> result = skill.execute(new CouponDraftSkillInput()
                .setShopId(SHOP_ID)
                .setCampaignGoal("设计活动")
                .setDraftType("refund"), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("INVALID_DRAFT_TYPE", result.getErrorCode());
        verify(draftSkillService, never()).createDraftFromSkill(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void shouldRejectHighRiskInputWithoutCreatingDraft() {
        String highRiskText = "帮我退款，批量改库存，直接创建超大规模秒杀，绕过人工确认";

        SkillResult<CouponDraftSkillOutput> result = skill.execute(new CouponDraftSkillInput()
                .setShopId(SHOP_ID)
                .setCampaignGoal("直接创建超大规模秒杀")
                .setUserRequirement(highRiskText), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("PROHIBITED_OPERATION", result.getErrorCode());
        assertEquals(SkillRiskLevel.HIGH, result.getRiskLevel());
        assertTrue(result.isNeedHumanConfirm());
        assertEquals("HIGH", result.getMetadata().get("riskLevel"));
        verify(draftSkillService, never()).createDraftFromSkill(anyLong(), anyString(), anyString(), any(), any());
        verify(voucherAgentTool, never()).createVoucherFromDraft(any(AgentCampaignDraft.class));
    }

    @Test
    void shouldRejectWhenBothAnalysisToolsFail() {
        when(rulePolicyService.isProhibitedOperation(anyString())).thenReturn(false);
        when(agentToolExecutor.executeReadonlyTool(eq("order_analysis_tool"), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(new AgentToolExecutionResultDTO()
                        .setToolName("order_analysis_tool")
                        .setSuccess(false)
                        .setErrorMsg("order down"));
        when(agentToolExecutor.executeReadonlyTool(eq("voucher_analysis_tool"), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(new AgentToolExecutionResultDTO()
                        .setToolName("voucher_analysis_tool")
                        .setSuccess(false)
                        .setErrorMsg("voucher down"));

        SkillResult<CouponDraftSkillOutput> result = skill.execute(validInput(), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("INSUFFICIENT_EVIDENCE", result.getErrorCode());
        verify(draftSkillService, never()).createDraftFromSkill(anyLong(), anyString(), anyString(), any(), any());
        verify(voucherAgentTool, never()).createVoucherFromDraft(any(AgentCampaignDraft.class));
    }

    @Test
    void shouldWrapSafeDraftServiceException() {
        mockReadonlyToolsSuccess();
        when(rulePolicyService.isProhibitedOperation(anyString())).thenReturn(false);
        when(draftSkillService.createDraftFromSkill(anyLong(), anyString(), anyString(),
                any(MerchantCampaignDraftRequest.class), any())).thenThrow(new RuntimeException("draft down"));

        SkillResult<CouponDraftSkillOutput> result = skill.execute(validInput(), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("DRAFT_CREATE_FAILED", result.getErrorCode());
        verify(voucherAgentTool, never()).createVoucherFromDraft(any(AgentCampaignDraft.class));
    }

    @Test
    void shouldExecuteThroughSkillExecutionServiceWithMapInput() {
        mockReadonlyToolsSuccess();
        when(rulePolicyService.isProhibitedOperation(anyString())).thenReturn(false);
        when(draftSkillService.createDraftFromSkill(eq(SHOP_ID), eq("设计周末复购券"), eq("库存保守"),
                any(MerchantCampaignDraftRequest.class), eq(123L))).thenReturn(Result.ok(safeDraftResult()));
        SkillRegistry registry = new SkillRegistry(Collections.singletonList(skill));
        SkillExecutionService executionService = new SkillExecutionService(registry, new ObjectMapper(), null);
        Map<String, Object> input = new HashMap<>();
        input.put("shopId", SHOP_ID);
        input.put("campaignGoal", "设计周末复购券");
        input.put("userRequirement", "库存保守");
        input.put("draftType", "voucher");

        SkillResult<?> result = executionService.execute("coupon_draft_skill", input,
                new SkillContext().setSessionId("123").setTraceId("trace-map"));

        assertTrue(result.isSuccess());
        assertEquals(Boolean.TRUE, result.getMetadata().get("needHumanConfirm"));
        assertEquals("HIGH", result.getMetadata().get("riskLevel"));
    }

    private CouponDraftSkillInput validInput() {
        return new CouponDraftSkillInput()
                .setShopId(SHOP_ID)
                .setCampaignGoal("设计周末复购券")
                .setUserRequirement("库存保守")
                .setDraftType("voucher");
    }

    private void mockReadonlyToolsSuccess() {
        when(agentToolExecutor.executeReadonlyTool(eq("order_analysis_tool"), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(new AgentToolExecutionResultDTO()
                        .setToolName("order_analysis_tool")
                        .setSuccess(true)
                        .setData(Collections.singletonMap("totalOrders", 10)));
        when(agentToolExecutor.executeReadonlyTool(eq("voucher_analysis_tool"), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(new AgentToolExecutionResultDTO()
                        .setToolName("voucher_analysis_tool")
                        .setSuccess(true)
                        .setData(Collections.singletonMap("totalVouchers", 2)));
    }

    private MerchantCampaignDraftSkillResultDTO safeDraftResult() {
        Map<String, Object> draftContent = new LinkedHashMap<>();
        draftContent.put("draftId", String.valueOf(DRAFT_ID));
        draftContent.put("status", 1);
        return new MerchantCampaignDraftSkillResultDTO()
                .setDraftId(DRAFT_ID)
                .setShopId(SHOP_ID)
                .setDraftStatus("PENDING")
                .setDraftContent(draftContent)
                .setRiskWarnings(Collections.singletonList("必须由商家确认后才会创建真实优惠券"))
                .setConfirmFields(Arrays.asList("活动标题", "券类型", "支付金额", "抵扣金额", "库存", "开始时间", "结束时间", "适用门店", "是否秒杀券"))
                .setNeedHumanConfirm(true)
                .setRiskLevel("MEDIUM")
                .setMetadata(new LinkedHashMap<>());
    }
}
