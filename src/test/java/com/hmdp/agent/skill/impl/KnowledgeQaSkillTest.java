package com.hmdp.agent.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.skill.SkillContext;
import com.hmdp.agent.skill.SkillDefinition;
import com.hmdp.agent.skill.SkillExecutionService;
import com.hmdp.agent.skill.SkillRegistry;
import com.hmdp.agent.skill.SkillResult;
import com.hmdp.agent.skill.SkillRiskLevel;
import com.hmdp.agent.skill.dto.KnowledgeQaSkillInput;
import com.hmdp.agent.skill.dto.KnowledgeQaSkillOutput;
import com.hmdp.service.IMerchantAgentKnowledgeDocService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeQaSkillTest {

    @Mock
    private IMerchantAgentKnowledgeDocService knowledgeDocService;

    private KnowledgeQaSkill skill;

    @BeforeEach
    void setUp() {
        skill = new KnowledgeQaSkill(knowledgeDocService);
    }

    @Test
    void shouldExposeShopScopedDefinition() {
        SkillDefinition definition = skill.definition();

        assertEquals("knowledge_qa_skill", definition.getSkillName());
        assertTrue(definition.getAllowedTools().isEmpty());
        assertEquals(SkillRiskLevel.LOW, definition.getRiskLevel());
        assertFalse(definition.getNeedHumanConfirm());
        assertFalse(definition.getModelCallable());
    }

    @Test
    void shouldExecuteWithShopScopedRagRetrieval() {
        when(knowledgeDocService.retrieveForAgentForShop(eq(10143L), eq("voucher_plan"), eq("秒杀库存怎么设置"), eq(5)))
                .thenReturn(Collections.singletonList(knowledgeRow(10143L, "秒杀库存建议", 0.86D)));

        SkillResult<KnowledgeQaSkillOutput> result = skill.execute(new KnowledgeQaSkillInput()
                .setShopId(10143L)
                .setIntent("voucher_plan")
                .setQuestion("秒杀库存怎么设置")
                .setTopK(5), new SkillContext().setTraceId("trace-qa-1"));

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput());
        assertEquals(10143L, result.getOutput().getShopId());
        assertFalse(result.getOutput().getNoReliableHit());
        assertEquals(Boolean.TRUE, result.getMetadata().get("shopScoped"));
        assertEquals("retrieval_only", result.getMetadata().get("skillMode"));
        assertEquals(1, result.getMetadata().get("retrievedCount"));
        assertEquals(0.86D, result.getOutput().getBestVectorScore());
        assertEquals(0.86D, result.getOutput().getCalibratedConfidence());
        assertTrue(result.getUsedTools().isEmpty());
        verify(knowledgeDocService).retrieveForAgentForShop(10143L, "voucher_plan", "秒杀库存怎么设置", 5);
        verify(knowledgeDocService, never()).retrieveForAgent(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldRejectBlankShopId() {
        SkillResult<KnowledgeQaSkillOutput> result = skill.execute(new KnowledgeQaSkillInput()
                .setQuestion("秒杀库存怎么设置"), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("INVALID_SHOP_ID", result.getErrorCode());
        verify(knowledgeDocService, never()).retrieveForAgentForShop(eq(10143L), anyString(), anyString(), anyInt());
        verify(knowledgeDocService, never()).retrieveForAgent(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldRejectBlankQuestion() {
        SkillResult<KnowledgeQaSkillOutput> result = skill.execute(new KnowledgeQaSkillInput()
                .setShopId(10143L)
                .setQuestion("   "), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("INVALID_QUESTION", result.getErrorCode());
        verify(knowledgeDocService, never()).retrieveForAgentForShop(eq(10143L), anyString(), anyString(), anyInt());
        verify(knowledgeDocService, never()).retrieveForAgent(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldReturnLowConfidenceAnswerWhenNoReliableHit() {
        when(knowledgeDocService.retrieveForAgentForShop(eq(10143L), eq("operation_chat"), eq("有没有会员规则"), eq(5)))
                .thenReturn(Collections.<Map<String, Object>>emptyList());

        SkillResult<KnowledgeQaSkillOutput> result = skill.execute(new KnowledgeQaSkillInput()
                .setShopId(10143L)
                .setQuestion("有没有会员规则"), new SkillContext());

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().getNoReliableHit());
        assertTrue(result.getOutput().getRetrievedChunks().isEmpty());
        assertEquals("未找到足够可靠的商家知识库依据，建议补充相关知识后再回答。", result.getOutput().getAnswer());
        assertEquals(0, result.getMetadata().get("retrievedCount"));
        verify(knowledgeDocService).retrieveForAgentForShop(10143L, "operation_chat", "有没有会员规则", 5);
        verify(knowledgeDocService, never()).retrieveForAgent(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldRespectNoReliableHitFlagFromRagResult() {
        Map<String, Object> row = knowledgeRow(null, "公共规则", 0.42D);
        row.put("noReliableHit", true);
        when(knowledgeDocService.retrieveForAgentForShop(eq(10143L), eq("operation_chat"), eq("风险规则是什么"), eq(5)))
                .thenReturn(Collections.singletonList(row));

        SkillResult<KnowledgeQaSkillOutput> result = skill.execute(new KnowledgeQaSkillInput()
                .setShopId(10143L)
                .setQuestion("风险规则是什么"), new SkillContext());

        assertTrue(result.isSuccess());
        assertTrue(result.getOutput().getNoReliableHit());
        assertEquals("未找到足够可靠的商家知识库依据，建议补充相关知识后再回答。", result.getOutput().getAnswer());
        verify(knowledgeDocService, never()).retrieveForAgent(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldWrapRagServiceException() {
        when(knowledgeDocService.retrieveForAgentForShop(eq(10143L), eq("operation_chat"), eq("活动规则"), eq(5)))
                .thenThrow(new RuntimeException("rag down"));

        SkillResult<KnowledgeQaSkillOutput> result = skill.execute(new KnowledgeQaSkillInput()
                .setShopId(10143L)
                .setQuestion("活动规则"), new SkillContext());

        assertFalse(result.isSuccess());
        assertEquals("RAG_RETRIEVE_FAILED", result.getErrorCode());
        assertNotNull(result.getErrorMessage());
        verify(knowledgeDocService).retrieveForAgentForShop(10143L, "operation_chat", "活动规则", 5);
        verify(knowledgeDocService, never()).retrieveForAgent(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldExecuteThroughSkillExecutionServiceWithMapInput() {
        when(knowledgeDocService.retrieveForAgentForShop(eq(10143L), eq("voucher_plan"), eq("秒杀活动怎么做"), eq(3)))
                .thenReturn(Collections.singletonList(knowledgeRow(10143L, "秒杀活动规则", 0.91D)));
        SkillRegistry registry = new SkillRegistry(Collections.singletonList(skill));
        SkillExecutionService executionService = new SkillExecutionService(registry, new ObjectMapper(), null);
        Map<String, Object> input = new HashMap<>();
        input.put("shopId", 10143L);
        input.put("intent", "voucher_plan");
        input.put("question", "秒杀活动怎么做");
        input.put("topK", 3);

        SkillResult<?> result = executionService.execute("knowledge_qa_skill", input, new SkillContext().setTraceId("trace-map"));

        assertTrue(result.isSuccess());
        assertNotNull(result.getOutput());
        assertEquals(Boolean.TRUE, result.getMetadata().get("shopScoped"));
        verify(knowledgeDocService).retrieveForAgentForShop(10143L, "voucher_plan", "秒杀活动怎么做", 3);
        verify(knowledgeDocService, never()).retrieveForAgent(anyString(), anyString(), anyInt());
    }

    @Test
    void shouldCapTopKToSafeUpperBound() {
        when(knowledgeDocService.retrieveForAgentForShop(eq(10143L), eq("operation_chat"), eq("活动规则"), eq(20)))
                .thenReturn(Collections.singletonList(knowledgeRow(10143L, "活动规则", 0.70D)));

        SkillResult<KnowledgeQaSkillOutput> result = skill.execute(new KnowledgeQaSkillInput()
                .setShopId(10143L)
                .setQuestion("活动规则")
                .setTopK(100), new SkillContext());

        assertTrue(result.isSuccess());
        assertEquals(20, result.getOutput().getTopK());
        verify(knowledgeDocService).retrieveForAgentForShop(10143L, "operation_chat", "活动规则", 20);
    }

    private Map<String, Object> knowledgeRow(Long shopId, String title, Double confidence) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("shopId", shopId);
        row.put("title", title);
        row.put("content", title + "内容");
        row.put("similarityScore", confidence);
        row.put("noReliableHit", false);
        return row;
    }
}
