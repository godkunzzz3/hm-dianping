package com.hmdp.agent.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.skill.SkillContext;
import com.hmdp.agent.skill.SkillDefinition;
import com.hmdp.agent.skill.SkillExecutionService;
import com.hmdp.agent.skill.SkillRegistry;
import com.hmdp.agent.skill.SkillResult;
import com.hmdp.agent.skill.SkillRiskLevel;
import com.hmdp.agent.skill.dto.MerchantDiagnosisSkillInput;
import com.hmdp.agent.skill.dto.MerchantDiagnosisSkillOutput;
import com.hmdp.dto.AgentToolDefinitionDTO;
import com.hmdp.dto.AgentToolExecutionRequestDTO;
import com.hmdp.dto.AgentToolExecutionResultDTO;
import com.hmdp.tool.AgentToolExecutor;
import com.hmdp.tool.AgentToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MerchantDiagnosisSkillTest {

    @Mock
    private AgentToolExecutor agentToolExecutor;
    @Mock
    private AgentToolRegistry agentToolRegistry;

    private MerchantDiagnosisSkill skill;

    @BeforeEach
    void setUp() {
        skill = new MerchantDiagnosisSkill(agentToolExecutor, agentToolRegistry, new ObjectMapper());
    }

    @Test
    void shouldExposeReadonlyDefinition() {
        SkillDefinition definition = skill.definition();

        assertEquals("merchant_diagnosis_skill", definition.getSkillName());
        assertEquals(SkillRiskLevel.LOW, definition.getRiskLevel());
        assertFalse(definition.getNeedHumanConfirm());
        assertFalse(definition.getModelCallable());
        assertTrue(definition.getAllowedTools().contains("shop_profile_tool"));
        assertTrue(definition.getAllowedTools().contains("order_analysis_tool"));
        assertTrue(definition.getAllowedTools().contains("voucher_analysis_tool"));
        assertTrue(definition.getAllowedTools().contains("review_content_tool"));
        assertFalse(definition.getAllowedTools().contains("operation_diagnosis_tool"));
        assertFalse(definition.getAllowedTools().contains("voucher_campaign_tool"));
    }

    @Test
    void shouldExecuteReadonlyToolsSuccessfully() {
        mockReadonlyDefinitions();
        mockToolSuccess("shop_profile_tool", mapOf("name", "测试店铺"));
        mockToolSuccess("order_analysis_tool", mapOf("totalOrders", 12));
        mockToolSuccess("voucher_analysis_tool", mapOf("totalVouchers", 3));
        mockToolSuccess("review_content_tool", mapOf("blogCount", 5));

        SkillResult<MerchantDiagnosisSkillOutput> result = skill.execute(new MerchantDiagnosisSkillInput()
                .setShopId(10143L)
                .setTimeRange("last_7_days")
                .setUserQuestion("帮我诊断经营情况"), new SkillContext().setTraceId("trace-1"));

        assertTrue(result.isSuccess());
        assertEquals(SkillRiskLevel.LOW, result.getRiskLevel());
        assertFalse(result.isNeedHumanConfirm());
        assertEquals(4, result.getUsedTools().size());
        assertTrue(result.getUsedTools().contains("shop_profile_tool"));
        assertTrue(result.getUsedTools().contains("order_analysis_tool"));
        assertTrue(result.getUsedTools().contains("voucher_analysis_tool"));
        assertTrue(result.getUsedTools().contains("review_content_tool"));
        assertNotNull(result.getOutput());
        assertFalse(result.getOutput().getShopProfile().isEmpty());
        assertFalse(result.getOutput().getOrderStats().isEmpty());
        assertFalse(result.getOutput().getKeyFindings().isEmpty());
        assertFalse(result.getOutput().getFindings().isEmpty());
        assertNotNull(result.getOutput().getFindings().get(0).get("evidence"));
        assertNotNull(result.getOutput().getConfidenceFactors().get("finalConfidence"));

        verify(agentToolExecutor).executeReadonlyTool(eq("shop_profile_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor).executeReadonlyTool(eq("order_analysis_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor).executeReadonlyTool(eq("voucher_analysis_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor).executeReadonlyTool(eq("review_content_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor, never()).executeReadonlyTool(eq("operation_diagnosis_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor, never()).executeReadonlyTool(eq("voucher_campaign_tool"), any(AgentToolExecutionRequestDTO.class));
    }

    @Test
    void shouldRejectBlankShopId() {
        SkillResult<MerchantDiagnosisSkillOutput> result = skill.execute(new MerchantDiagnosisSkillInput(), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("INVALID_SHOP_ID", result.getErrorCode());
    }

    @Test
    void shouldReturnFailureWhenCoreToolFails() {
        mockReadonlyDefinitions();
        when(agentToolExecutor.executeReadonlyTool(eq("shop_profile_tool"), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(failure("shop_profile_tool", "shop profile failed"));

        SkillResult<MerchantDiagnosisSkillOutput> result = skill.execute(new MerchantDiagnosisSkillInput()
                .setShopId(10143L), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("SHOP_PROFILE_FAILED", result.getErrorCode());
        assertNotNull(result.getErrorMessage());
        verify(agentToolExecutor, never()).executeReadonlyTool(eq("operation_diagnosis_tool"), any(AgentToolExecutionRequestDTO.class));
        verify(agentToolExecutor, never()).executeReadonlyTool(eq("voucher_campaign_tool"), any(AgentToolExecutionRequestDTO.class));
    }

    @Test
    void shouldKeepSuccessWhenNonCoreToolFails() {
        mockReadonlyDefinitions();
        mockToolSuccess("shop_profile_tool", mapOf("name", "测试店铺"));
        mockToolSuccess("order_analysis_tool", mapOf("totalOrders", 12));
        when(agentToolExecutor.executeReadonlyTool(eq("voucher_analysis_tool"), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(failure("voucher_analysis_tool", "voucher failed"));
        mockToolSuccess("review_content_tool", mapOf("blogCount", 5));

        SkillResult<MerchantDiagnosisSkillOutput> result = skill.execute(new MerchantDiagnosisSkillInput()
                .setShopId(10143L), new SkillContext());

        assertTrue(result.isSuccess());
        assertEquals(Boolean.TRUE, result.getMetadata().get("partialFailure"));
        assertTrue(result.getOutput().getVoucherStats().isEmpty());
        assertTrue(result.getUsedTools().contains("shop_profile_tool"));
        assertTrue(result.getUsedTools().contains("order_analysis_tool"));
        assertTrue(result.getUsedTools().contains("review_content_tool"));
        assertFalse(result.getUsedTools().contains("voucher_analysis_tool"));
    }

    @Test
    void shouldExecuteThroughSkillExecutionServiceWithMapInput() {
        mockReadonlyDefinitions();
        mockToolSuccess("shop_profile_tool", mapOf("name", "测试店铺"));
        mockToolSuccess("order_analysis_tool", mapOf("totalOrders", 12));
        mockToolSuccess("voucher_analysis_tool", mapOf("totalVouchers", 3));
        mockToolSuccess("review_content_tool", mapOf("blogCount", 5));
        SkillRegistry registry = new SkillRegistry(Collections.singletonList(skill));
        SkillExecutionService executionService = new SkillExecutionService(registry, new ObjectMapper(), null);
        Map<String, Object> input = new HashMap<>();
        input.put("shopId", 10143L);
        input.put("timeRange", "last_30_days");
        input.put("userQuestion", "帮我分析最近经营情况");

        SkillResult<?> result = executionService.execute("merchant_diagnosis_skill", input, new SkillContext());

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput());
        assertEquals(4, result.getUsedTools().size());
    }

    private void mockReadonlyDefinitions() {
        when(agentToolRegistry.listDefinitions()).thenReturn(Arrays.asList(
                readonlyDefinition("shop_profile_tool"),
                readonlyDefinition("order_analysis_tool"),
                readonlyDefinition("voucher_analysis_tool"),
                readonlyDefinition("review_content_tool"),
                readonlyDefinition("operation_diagnosis_tool"),
                new AgentToolDefinitionDTO()
                        .setName("voucher_campaign_tool")
                        .setToolType("draft")
                        .setAccessLevel("write")
                        .setWriteDatabase(true)
                        .setRequireMerchantConfirm(true)
        ));
    }

    private AgentToolDefinitionDTO readonlyDefinition(String toolName) {
        return new AgentToolDefinitionDTO()
                .setName(toolName)
                .setToolType("readonly")
                .setAccessLevel("read")
                .setWriteDatabase(false)
                .setRequireMerchantConfirm(false)
                .setModelCallable(true);
    }

    private void mockToolSuccess(String toolName, Map<String, Object> data) {
        when(agentToolExecutor.executeReadonlyTool(eq(toolName), any(AgentToolExecutionRequestDTO.class)))
                .thenReturn(new AgentToolExecutionResultDTO()
                        .setToolName(toolName)
                        .setSuccess(true)
                        .setData(data));
    }

    private AgentToolExecutionResultDTO failure(String toolName, String errorMsg) {
        return new AgentToolExecutionResultDTO()
                .setToolName(toolName)
                .setSuccess(false)
                .setErrorMsg(errorMsg);
    }

    private Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
}
