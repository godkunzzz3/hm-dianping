package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.agent.MerchantAgentModelClient;
import com.hmdp.agent.MerchantAgentPromptTemplateService;
import com.hmdp.agent.MerchantAgentRulePolicyService;
import com.hmdp.agent.MerchantAgentToolCallingService;
import com.hmdp.agent.skill.SkillContext;
import com.hmdp.agent.skill.SkillExecutionService;
import com.hmdp.agent.skill.SkillResult;
import com.hmdp.agent.skill.dto.CouponDraftSkillInput;
import com.hmdp.agent.skill.dto.CouponDraftSkillOutput;
import com.hmdp.agent.skill.dto.MerchantDiagnosisSkillInput;
import com.hmdp.dto.*;
import com.hmdp.entity.AgentActionLog;
import com.hmdp.entity.AgentCampaignDraft;
import com.hmdp.entity.AgentMessage;
import com.hmdp.entity.AgentSession;
import com.hmdp.entity.AgentSuggestion;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.service.IMerchantAgentFacadeService;
import com.hmdp.service.IMerchantAgentActionLogService;
import com.hmdp.service.IMerchantAgentMessageService;
import com.hmdp.service.IMerchantAgentSessionService;
import com.hmdp.service.IMerchantAgentSuggestionService;
import com.hmdp.service.IMerchantAgentKnowledgeDocService;
import com.hmdp.service.IMerchantAgentMemoryService;
import com.hmdp.service.AgentWorkflowRecorderService;
import com.hmdp.service.IMerchantCampaignDraftService;
import com.hmdp.service.IMerchantService;
import com.hmdp.service.MerchantCampaignDraftValidator;
import com.hmdp.tool.OrderAgentTool;
import com.hmdp.tool.ReviewAgentTool;
import com.hmdp.tool.ShopAgentTool;
import com.hmdp.tool.AgentToolRegistry;
import com.hmdp.tool.AgentToolExecutor;
import com.hmdp.tool.VoucherAgentTool;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 商家运营 Agent 门面服务实现。
 *
 * <p>门面层负责跨表业务编排，例如：创建会话、保存用户消息、调用工具、保存建议、
 * 记录审计日志。当前先注册为 Spring Bean，后续实现运营报告接口时在这里继续扩展。</p>
 */
@Service
public class MerchantAgentFacadeServiceImpl implements IMerchantAgentFacadeService {

    /**
     * 活动草稿状态：待商家确认。
     */
    private static final int DRAFT_STATUS_PENDING = 1;

    /**
     * 活动草稿状态：已经确认并创建真实优惠券。
     */
    private static final int DRAFT_STATUS_CREATED = 2;

    /**
     * 活动草稿状态：商家已拒绝。
     */
    private static final int DRAFT_STATUS_REJECTED = 3;

    /**
     * 会话标题只用于工作台展示，控制长度可以避免侧边栏布局被长文本撑坏。
     */
    private static final int SESSION_TITLE_MAX_LENGTH = 40;

    @Resource
    private ShopAgentTool shopAgentTool;
    @Resource
    private OrderAgentTool orderAgentTool;
    @Resource
    private VoucherAgentTool voucherAgentTool;
    @Resource
    private ReviewAgentTool reviewAgentTool;
    @Resource
    private IMerchantAgentSessionService agentSessionService;
    @Resource
    private IMerchantAgentMessageService agentMessageService;
    @Resource
    private IMerchantAgentSuggestionService agentSuggestionService;
    @Resource
    private IMerchantAgentKnowledgeDocService agentKnowledgeDocService;
    @Resource
    private IMerchantCampaignDraftService campaignDraftService;
    @Resource
    private IMerchantAgentActionLogService agentActionLogService;
    @Resource
    private IMerchantService merchantService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private AgentToolRegistry agentToolRegistry;
    @Resource
    private AgentToolExecutor agentToolExecutor;
    @Resource
    private MerchantAgentModelClient merchantAgentModelClient;
    @Resource
    private MerchantAgentPromptTemplateService promptTemplateService;
    @Resource
    private MerchantAgentToolCallingService toolCallingService;
    @Resource
    private AgentWorkflowRecorderService agentWorkflowRecorderService;
    @Resource
    private MerchantAgentRulePolicyService rulePolicyService;
    @Resource
    private IMerchantAgentMemoryService agentMemoryService;
    @Resource
    private MerchantCampaignDraftValidator campaignDraftValidator;
    @Resource
    private SkillExecutionService skillExecutionService;

    @Override
    public Result queryAgentTools() {
        return Result.ok(agentToolRegistry.listDefinitions());
    }

    @Override
    public Result queryModelCallableAgentTools() {
        return Result.ok(agentToolRegistry.listModelCallableDefinitions());
    }

    @Override
    @Transactional
    public Result generateOperationReport(Long shopId, String dateRange) {
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        Shop shop = shopAgentTool.getShop(shopId);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(shopId)) {
            return Result.fail("无权管理该店铺");
        }

        DateRange range = resolveDateRange(dateRange);
        List<Voucher> vouchers = voucherAgentTool.queryShopVouchers(shopId);
        List<Long> voucherIds = vouchers.stream().map(Voucher::getId).collect(Collectors.toList());
        List<VoucherOrder> orders = orderAgentTool.queryOrders(voucherIds, range.getStartTime());
        Map<Long, Voucher> voucherMap = vouchers.stream().collect(Collectors.toMap(Voucher::getId, voucher -> voucher));
        List<SeckillVoucher> seckillVouchers = voucherAgentTool.querySeckillVouchers(voucherIds);
        List<Blog> blogs = reviewAgentTool.queryShopBlogs(shopId);
        List<BlogComments> comments = reviewAgentTool.queryComments(blogs);

        ShopProfileDTO shopProfile = shopAgentTool.buildShopProfile(shop);
        OrderStatsDTO orderAnalysis = orderAgentTool.buildOrderAnalysis(orders, voucherMap);
        VoucherStatsDTO voucherAnalysis = voucherAgentTool.buildVoucherAnalysis(vouchers, seckillVouchers);
        ReviewStatsDTO reviewAnalysis = reviewAgentTool.buildReviewAnalysis(blogs, comments);
        List<AgentRecommendationDTO> recommendations = buildRecommendations(shop, orders, vouchers, seckillVouchers, blogs, comments);
        String summary = buildSummary(shop, range, orderAnalysis, voucherAnalysis, reviewAnalysis, recommendations);

        Long sessionId = nextAgentId();
        Long merchantId = currentMerchantId();
        AgentSession session = new AgentSession()
                .setId(sessionId)
                .setShopId(shopId)
                .setMerchantId(merchantId)
                .setTitle(shop.getName() + "运营报告")
                .setScene("operation_report")
                .setStatus(2);
        agentSessionService.save(session);

        saveMessage(sessionId, shopId, "user", "生成" + range.getLabel() + "运营报告", null, null, null);
        Long suggestionId = saveOperationSuggestion(sessionId, shopId, summary, recommendations);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sessionId", String.valueOf(sessionId));
        report.put("suggestionId", String.valueOf(suggestionId));
        report.put("shopId", shopId);
        report.put("dateRange", range.getCode());
        report.put("startTime", range.getStartTime());
        report.put("endTime", range.getEndTime());
        report.put("summary", summary);
        report.put("shopProfile", shopProfile);
        report.put("orderAnalysis", orderAnalysis);
        report.put("voucherAnalysis", voucherAnalysis);
        report.put("reviewAnalysis", reviewAnalysis);
        report.put("recommendations", recommendations);

        saveMessage(sessionId, shopId, "assistant", summary, null, null, toSimpleJson(report));
        recordAction(sessionId, shopId, merchantId, "generate_operation_report", "suggestion", suggestionId, report);
        return Result.ok(report);
    }

    @Override
    public Result queryShopSessions(Long shopId) {
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        if (shopAgentTool.getShop(shopId) == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(shopId)) {
            return Result.fail("无权管理该店铺");
        }

        List<AgentSession> sessions = agentSessionService.query()
                .eq("shop_id", shopId)
                .orderByDesc("create_time")
                .last("LIMIT 50")
                .list();
        if (sessions.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> sessionIds = sessions.stream().map(AgentSession::getId).collect(Collectors.toList());
        Map<Long, Integer> messageCountMap = countMessagesBySession(sessionIds);
        Map<Long, AgentSuggestion> latestSuggestionMap = queryLatestSuggestionBySession(sessionIds);

        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentSession session : sessions) {
            AgentSuggestion suggestion = latestSuggestionMap.get(session.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            // 雪花 ID 超过 JS 安全整数范围，面向前端的 id 一律补充字符串字段。
            row.put("id", session.getId());
            row.put("sessionId", String.valueOf(session.getId()));
            row.put("shopId", session.getShopId());
            row.put("merchantId", String.valueOf(session.getMerchantId()));
            row.put("title", session.getTitle());
            row.put("scene", session.getScene());
            row.put("sceneName", resolveSceneName(session.getScene()));
            row.put("status", session.getStatus());
            row.put("statusName", resolveSessionStatusName(session.getStatus()));
            row.put("messageCount", messageCountMap.getOrDefault(session.getId(), 0));
            row.put("latestSuggestion", suggestion == null ? "" : suggestion.getSummary());
            row.put("createTime", session.getCreateTime());
            row.put("updateTime", session.getUpdateTime());
            result.add(row);
        }
        return Result.ok(result);
    }

    @Override
    public Result querySessionMessages(Long sessionId) {
        if (sessionId == null) {
            return Result.fail("会话id不能为空");
        }
        AgentSession session = agentSessionService.getById(sessionId);
        if (session == null) {
            return Result.fail("会话不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(session.getShopId())) {
            return Result.fail("无权查看该会话");
        }

        List<AgentMessage> messages = agentMessageService.query()
                .eq("session_id", sessionId)
                .orderByAsc("create_time")
                .list();

        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentMessage message : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", message.getId());
            row.put("messageId", String.valueOf(message.getId()));
            row.put("sessionId", String.valueOf(message.getSessionId()));
            row.put("shopId", message.getShopId());
            row.put("role", message.getRole());
            row.put("content", message.getContent());
            row.put("toolName", message.getToolName());
            row.put("toolArgs", message.getToolArgs());
            row.put("toolResult", message.getToolResult());
            row.put("createTime", message.getCreateTime());
            result.add(row);
        }
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result renameSession(Long sessionId, String title) {
        if (sessionId == null) {
            return Result.fail("会话id不能为空");
        }
        String newTitle = title == null ? "" : title.trim();
        if (newTitle.isEmpty()) {
            return Result.fail("会话标题不能为空");
        }
        if (newTitle.length() > SESSION_TITLE_MAX_LENGTH) {
            return Result.fail("会话标题不能超过" + SESSION_TITLE_MAX_LENGTH + "个字符");
        }
        AgentSession session = agentSessionService.getById(sessionId);
        if (session == null) {
            return Result.fail("会话不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(session.getShopId())) {
            return Result.fail("无权修改该会话");
        }

        agentSessionService.updateById(new AgentSession()
                .setId(sessionId)
                .setTitle(newTitle)
                .setUpdateTime(LocalDateTime.now()));
        session.setTitle(newTitle);
        session.setUpdateTime(LocalDateTime.now());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", String.valueOf(sessionId));
        result.put("shopId", session.getShopId());
        result.put("title", newTitle);
        result.put("message", "历史会话已重命名");
        recordAction(sessionId, session.getShopId(), currentMerchantId(),
                "rename_agent_session", "session", sessionId, result);
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result deleteSession(Long sessionId) {
        if (sessionId == null) {
            return Result.fail("会话id不能为空");
        }
        AgentSession session = agentSessionService.getById(sessionId);
        if (session == null) {
            return Result.fail("会话不存在或已删除");
        }
        if (!merchantService.hasCurrentUserShopPermission(session.getShopId())) {
            return Result.fail("无权删除该会话");
        }

        long deletedMessages = agentMessageService.count(
                new QueryWrapper<AgentMessage>().eq("session_id", sessionId));

        // 学习重点：会话属于“展示和上下文记录”，删除时只清理会话和消息；
        // 建议、草稿、真实优惠券和审计日志是业务链路的一部分，不能跟着级联删除。
        agentMessageService.remove(new QueryWrapper<AgentMessage>().eq("session_id", sessionId));
        agentSessionService.removeById(sessionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", String.valueOf(sessionId));
        result.put("shopId", session.getShopId());
        result.put("deletedMessages", deletedMessages);
        result.put("message", "历史会话已删除");
        recordAction(sessionId, session.getShopId(), currentMerchantId(),
                "delete_agent_session", "session", sessionId, result);
        return Result.ok(result);
    }

    @Override
    public Result queryShopSuggestions(Long shopId) {
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        if (shopAgentTool.getShop(shopId) == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(shopId)) {
            return Result.fail("无权管理该店铺");
        }

        List<AgentSuggestion> suggestions = agentSuggestionService.query()
                .eq("shop_id", shopId)
                .orderByDesc("create_time")
                .last("LIMIT 50")
                .list();

        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentSuggestion suggestion : suggestions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", suggestion.getId());
            row.put("suggestionId", String.valueOf(suggestion.getId()));
            row.put("sessionId", String.valueOf(suggestion.getSessionId()));
            row.put("shopId", suggestion.getShopId());
            row.put("type", suggestion.getSuggestionType());
            row.put("typeName", resolveSuggestionTypeName(suggestion.getSuggestionType()));
            row.put("title", suggestion.getTitle());
            row.put("summary", suggestion.getSummary());
            row.put("content", suggestion.getContent());
            row.put("confidenceScore", suggestion.getConfidenceScore());
            row.put("riskLevel", suggestion.getRiskLevel());
            row.put("riskLevelName", resolveRiskLevelName(suggestion.getRiskLevel()));
            row.put("status", suggestion.getStatus());
            row.put("statusName", resolveSuggestionStatusName(suggestion.getStatus()));
            row.put("createTime", suggestion.getCreateTime());
            row.put("updateTime", suggestion.getUpdateTime());
            result.add(row);
        }
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result deleteSuggestion(Long suggestionId) {
        if (suggestionId == null) {
            return Result.fail("建议id不能为空");
        }
        AgentSuggestion suggestion = agentSuggestionService.getById(suggestionId);
        if (suggestion == null) {
            return Result.fail("智能行动不存在或已删除");
        }
        if (!merchantService.hasCurrentUserShopPermission(suggestion.getShopId())) {
            return Result.fail("无权删除该智能行动");
        }

        long relatedDrafts = campaignDraftService.count(
                new QueryWrapper<AgentCampaignDraft>().eq("suggestion_id", suggestionId));
        agentSuggestionService.removeById(suggestionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionId", String.valueOf(suggestionId));
        result.put("shopId", suggestion.getShopId());
        result.put("relatedDrafts", relatedDrafts);
        result.put("message", relatedDrafts > 0
                ? "智能行动已删除，已生成的活动草稿仍保留"
                : "智能行动已删除");
        recordAction(suggestion.getSessionId(), suggestion.getShopId(), currentMerchantId(),
                "delete_agent_suggestion", "suggestion", suggestionId, result);
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result createCampaignDraft(Long suggestionId, MerchantCampaignDraftRequest request) {
        if (suggestionId == null) {
            return Result.fail("建议id不能为空");
        }
        AgentSuggestion suggestion = agentSuggestionService.getById(suggestionId);
        if (suggestion == null) {
            return Result.fail("Agent建议不存在");
        }
        if (suggestion.getStatus() != null && suggestion.getStatus() == 4) {
            return Result.fail("该建议已经执行，不能重复生成草稿");
        }
        Shop shop = shopAgentTool.getShop(suggestion.getShopId());
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(suggestion.getShopId())) {
            return Result.fail("无权管理该店铺");
        }

        AgentCampaignDraft draft = voucherAgentTool.buildCampaignDraft(suggestion, shop, request, nextAgentId());
        campaignDraftService.save(draft);
        agentSuggestionService.updateById(new AgentSuggestion()
                .setId(suggestion.getId())
                .setStatus(2));
        Map<String, Object> result = voucherAgentTool.draftToMap(draft);
        result.put("needConfirm", true);
        result.put("confirmed", false);
        result.put("humanInLoopMessage", "Agent 已保存活动草稿，等待商家确认后才会创建真实优惠券");
        recordAction(suggestion.getSessionId(), suggestion.getShopId(), currentMerchantId(),
                "create_campaign_draft", "draft", draft.getId(), result);
        return Result.ok(result);
    }

    @Override
    public Result queryShopDrafts(Long shopId) {
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        if (shopAgentTool.getShop(shopId) == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(shopId)) {
            return Result.fail("无权管理该店铺");
        }

        List<AgentCampaignDraft> drafts = campaignDraftService.query()
                .eq("shop_id", shopId)
                .orderByDesc("create_time")
                .last("LIMIT 50")
                .list();
        if (drafts.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentCampaignDraft draft : drafts) {
            // 复用工具层的 DTO/Map 转换，保证草稿详情和草稿列表返回字段一致。
            result.add(voucherAgentTool.draftToMap(draft));
        }
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result clearShopDrafts(Long shopId) {
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        if (shopAgentTool.getShop(shopId) == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(shopId)) {
            return Result.fail("无权管理该店铺");
        }

        List<AgentCampaignDraft> drafts = campaignDraftService.query()
                .eq("shop_id", shopId)
                .list();
        List<Long> deletableIds = drafts.stream()
                .filter(draft -> draft.getStatus() == null || draft.getStatus() != DRAFT_STATUS_CREATED)
                .map(AgentCampaignDraft::getId)
                .collect(Collectors.toList());
        long skippedCreated = drafts.size() - deletableIds.size();
        if (!deletableIds.isEmpty()) {
            campaignDraftService.removeByIds(deletableIds);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("shopId", shopId);
        result.put("deletedCount", deletableIds.size());
        result.put("skippedCreatedCount", skippedCreated);
        result.put("message", "已清空未创建真实活动的草稿");
        recordAction(null, shopId, currentMerchantId(),
                "clear_campaign_drafts", "draft", null, result);
        return Result.ok(result);
    }

    @Override
    public Result queryCampaignDraftDetail(Long draftId) {
        if (draftId == null) {
            return Result.fail("草稿id不能为空");
        }
        AgentCampaignDraft draft = campaignDraftService.getById(draftId);
        if (draft == null) {
            return Result.fail("活动草稿不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(draft.getShopId())) {
            return Result.fail("无权查看该草稿");
        }

        return Result.ok(voucherAgentTool.draftToMap(draft));
    }

    @Override
    @Transactional
    public Result deleteCampaignDraft(Long draftId) {
        if (draftId == null) {
            return Result.fail("草稿id不能为空");
        }
        AgentCampaignDraft draft = campaignDraftService.getById(draftId);
        if (draft == null) {
            return Result.fail("活动草稿不存在或已删除");
        }
        if (!merchantService.hasCurrentUserShopPermission(draft.getShopId())) {
            return Result.fail("无权删除该草稿");
        }
        if (draft.getStatus() != null && draft.getStatus() == DRAFT_STATUS_CREATED) {
            return Result.fail("该草稿已创建真实优惠券，不能删除审计记录");
        }

        AgentSuggestion suggestion = agentSuggestionService.getById(draft.getSuggestionId());
        campaignDraftService.removeById(draftId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftId", String.valueOf(draftId));
        result.put("shopId", draft.getShopId());
        result.put("status", draft.getStatus());
        result.put("message", "活动草稿已删除");
        recordAction(suggestion == null ? null : suggestion.getSessionId(), draft.getShopId(), currentMerchantId(),
                "delete_campaign_draft", "draft", draftId, result);
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result rejectCampaignDraft(Long draftId) {
        if (draftId == null) {
            return Result.fail("草稿id不能为空");
        }
        AgentCampaignDraft draft = campaignDraftService.getById(draftId);
        if (draft == null) {
            return Result.fail("活动草稿不存在");
        }
        if (draft.getStatus() != null && draft.getStatus() != 1) {
            return Result.fail("只有待确认草稿可以拒绝");
        }
        if (!merchantService.hasCurrentUserShopPermission(draft.getShopId())) {
            return Result.fail("无权管理该草稿");
        }

        AgentCampaignDraft updateDraft = new AgentCampaignDraft()
                .setId(draft.getId())
                .setStatus(3);
        campaignDraftService.updateById(updateDraft);
        draft.setStatus(3);

        Map<String, Object> result = voucherAgentTool.draftToMap(draft);
        result.put("needConfirm", true);
        result.put("confirmed", false);
        result.put("humanInLoopMessage", "Agent 已生成活动草稿，必须由商家确认后才会创建真实优惠券");
        result.put("message", "草稿已拒绝");
        AgentSuggestion suggestion = agentSuggestionService.getById(draft.getSuggestionId());
        Long sessionId = suggestion == null ? null : suggestion.getSessionId();
        recordAction(sessionId, draft.getShopId(), currentMerchantId(),
                "reject_campaign_draft", "draft", draft.getId(), result);
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result updateCampaignDraft(Long draftId, MerchantCampaignDraftRequest request) {
        if (draftId == null) {
            return Result.fail("草稿id不能为空");
        }
        if (request == null) {
            return Result.fail("草稿修改内容不能为空");
        }
        AgentCampaignDraft draft = campaignDraftService.getById(draftId);
        if (draft == null) {
            return Result.fail("活动草稿不存在");
        }
        if (draft.getStatus() != null && draft.getStatus() != 1) {
            return Result.fail("只有待确认草稿可以修改");
        }
        if (!merchantService.hasCurrentUserShopPermission(draft.getShopId())) {
            return Result.fail("无权管理该草稿");
        }
        Result validateResult = validateDraftUpdate(draft, request);
        if (!validateResult.getSuccess()) {
            return validateResult;
        }

        AgentCampaignDraft updateDraft = buildDraftUpdate(draft, request);
        campaignDraftService.updateById(updateDraft);
        AgentCampaignDraft freshDraft = campaignDraftService.getById(draftId);
        Map<String, Object> result = voucherAgentTool.draftToMap(freshDraft);
        result.put("needConfirm", true);
        result.put("confirmed", false);
        result.put("humanInLoopMessage", "草稿已更新，仍需商家确认后才会创建真实优惠券");
        result.put("message", "草稿已更新");
        AgentSuggestion suggestion = agentSuggestionService.getById(freshDraft.getSuggestionId());
        Long sessionId = suggestion == null ? null : suggestion.getSessionId();
        recordAction(sessionId, freshDraft.getShopId(), currentMerchantId(),
                "update_campaign_draft", "draft", freshDraft.getId(), result);
        return Result.ok(result);
    }

    @Override
    public Result queryDraftActions(Long draftId) {
        if (draftId == null) {
            return Result.fail("草稿id不能为空");
        }
        AgentCampaignDraft draft = campaignDraftService.getById(draftId);
        if (draft == null) {
            return Result.fail("活动草稿不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(draft.getShopId())) {
            return Result.fail("无权查看该草稿操作日志");
        }

        List<AgentActionLog> actionLogs = agentActionLogService.query()
                .eq("target_type", "draft")
                .eq("target_id", draftId)
                .orderByDesc("create_time")
                .list();
        return Result.ok(buildActionRows(actionLogs));
    }

    @Override
    public Result queryCampaignEffect(Long draftId) {
        if (draftId == null) {
            return Result.fail("草稿id不能为空");
        }
        AgentCampaignDraft draft = campaignDraftService.getById(draftId);
        if (draft == null) {
            return Result.fail("活动草稿不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(draft.getShopId())) {
            return Result.fail("无权查看该活动效果");
        }
        if (draft.getStatus() == null || draft.getStatus() != 2) {
            return Result.fail("活动还未创建，暂无效果数据");
        }

        Long voucherId = resolveConfirmedVoucherId(draft);
        if (voucherId == null) {
            return Result.fail("未找到草稿对应的真实优惠券，请确认活动是否创建成功");
        }

        Voucher voucher = resolveShopVoucher(draft.getShopId(), voucherId);
        if (voucher == null) {
            return Result.fail("真实优惠券不存在或不属于当前店铺");
        }

        LocalDateTime startTime = draft.getBeginTime() == null ? draft.getCreateTime() : draft.getBeginTime();
        List<VoucherOrder> orders = orderAgentTool.queryOrders(Collections.singletonList(voucherId), startTime);
        Map<Long, Voucher> voucherMap = Collections.singletonMap(voucherId, voucher);
        OrderStatsDTO orderStats = orderAgentTool.buildOrderAnalysis(orders, voucherMap);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draft", voucherAgentTool.draftToMap(draft));
        result.put("voucher", voucherToEffectMap(voucher));
        result.put("voucherId", String.valueOf(voucherId));
        result.put("startTime", startTime);
        result.put("endTime", LocalDateTime.now());
        result.put("campaignStatus", resolveCampaignStatus(draft));
        result.put("orderAnalysis", orderStats);
        result.put("effectLevel", resolveEffectLevel(orderStats));
        result.put("insight", buildCampaignEffectInsight(draft, voucher, orderStats));
        result.put("nextAction", buildCampaignNextAction(orderStats));

        recordAction(null, draft.getShopId(), currentMerchantId(),
                "query_campaign_effect", "draft", draft.getId(), result);
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result createEffectSuggestion(Long draftId, Boolean autoDraft) {
        if (draftId == null) {
            return Result.fail("草稿id不能为空");
        }
        AgentCampaignDraft draft = campaignDraftService.getById(draftId);
        if (draft == null) {
            return Result.fail("活动草稿不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(draft.getShopId())) {
            return Result.fail("无权管理该店铺");
        }
        if (draft.getStatus() == null || draft.getStatus() != 2) {
            return Result.fail("活动还未创建，暂不能生成复盘建议");
        }

        CampaignEffectContext context = buildCampaignEffectContext(draft);
        if (context.getVoucher() == null) {
            return Result.fail("未找到草稿对应的真实优惠券，请确认活动是否创建成功");
        }

        AgentRecommendationDTO recommendation = buildEffectRecommendation(draft, context);
        Long suggestionId = saveEffectSuggestion(draft, context, recommendation);
        AgentCampaignDraft nextDraft = null;
        if (Boolean.TRUE.equals(autoDraft)) {
            nextDraft = createDraftFromEffectSuggestion(suggestionId, recommendation, context);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionId", String.valueOf(suggestionId));
        result.put("recommendation", recommendation);
        result.put("draftId", nextDraft == null ? null : String.valueOf(nextDraft.getId()));
        result.put("draft", nextDraft == null ? null : voucherAgentTool.draftToMap(nextDraft));
        result.put("effectLevel", resolveEffectLevel(context.getOrderStats()));
        result.put("campaignStatus", resolveCampaignStatus(draft));
        result.put("orderAnalysis", context.getOrderStats());
        result.put("message", nextDraft == null ? "已生成复盘建议" : "已生成复盘建议和下一轮草稿");
        recordAction(null, draft.getShopId(), currentMerchantId(),
                "create_effect_suggestion", "suggestion", suggestionId, result);
        return Result.ok(result);
    }

    @Override
    public Result queryShopActions(Long shopId) {
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        if (shopAgentTool.getShop(shopId) == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(shopId)) {
            return Result.fail("无权查看该店铺操作日志");
        }

        List<AgentActionLog> actionLogs = agentActionLogService.query()
                .eq("shop_id", shopId)
                .orderByDesc("create_time")
                .last("LIMIT 50")
                .list();
        return Result.ok(buildActionRows(actionLogs));
    }

    private Result validateDraftUpdate(AgentCampaignDraft draft, MerchantCampaignDraftRequest request) {
        String title = request.getTitle() == null ? draft.getTitle() : request.getTitle();
        String subTitle = request.getSubTitle() == null ? draft.getSubTitle() : request.getSubTitle();
        String rules = request.getRules() == null ? draft.getRules() : request.getRules();
        Long payValue = request.getPayValue() == null ? draft.getPayValue() : request.getPayValue();
        Long actualValue = request.getActualValue() == null ? draft.getActualValue() : request.getActualValue();
        Integer stock = request.getStock() == null ? draft.getStock() : request.getStock();
        LocalDateTime beginTime = request.getBeginTime() == null ? draft.getBeginTime() : request.getBeginTime();
        LocalDateTime endTime = request.getEndTime() == null ? draft.getEndTime() : request.getEndTime();

        return validateDraftBusinessFields(draft.getDraftType(), title, subTitle, rules, payValue, actualValue, stock, beginTime, endTime, false);
    }

    /**
     * 活动草稿核心业务校验。
     *
     * <p>这层校验的目的不是替代数据库约束，而是在写库或创建真实券之前给出更可读的业务错误。
     * Agent 输出、前端输入都可能不稳定，所以金额、库存、时间、短文本长度都要在后端兜住。</p>
     */
    private Result validateDraftBusinessFields(String draftType,
                                               String title,
                                               String subTitle,
                                               String rules,
                                               Long payValue,
                                               Long actualValue,
                                               Integer stock,
                                               LocalDateTime beginTime,
                                               LocalDateTime endTime,
                                               boolean confirmStage) {
        return campaignDraftValidator.validateDraftBusinessFields(draftType, title, subTitle, rules, payValue,
                actualValue, stock, beginTime, endTime, confirmStage);
    }

    private AgentCampaignDraft buildDraftUpdate(AgentCampaignDraft draft, MerchantCampaignDraftRequest request) {
        AgentCampaignDraft updateDraft = new AgentCampaignDraft().setId(draft.getId());
        updateDraft.setTitle(request.getTitle());
        updateDraft.setSubTitle(request.getSubTitle());
        updateDraft.setPayValue(request.getPayValue());
        updateDraft.setActualValue(request.getActualValue());
        updateDraft.setStock(request.getStock());
        updateDraft.setBeginTime(request.getBeginTime());
        updateDraft.setEndTime(request.getEndTime());
        updateDraft.setRules(request.getRules());
        return updateDraft;
    }

    /**
     * 确认草稿前做最终业务校验。
     *
     * <p>草稿可以被 Agent 生成，也可以被商家二次编辑，所以真正创建优惠券前必须再校验一次。
     * 这里拦住金额倒挂、活动时间错误、秒杀库存缺失等问题，避免生成无法售卖的真实券。</p>
     */
    private Result validateDraftBeforeConfirm(AgentCampaignDraft draft) {
        return validateDraftBusinessFields(
                draft.getDraftType(),
                draft.getTitle(),
                draft.getSubTitle(),
                draft.getRules(),
                draft.getPayValue(),
                draft.getActualValue(),
                draft.getStock(),
                draft.getBeginTime(),
                draft.getEndTime(),
                true
        );
    }

    @Override
    @Transactional
    public Result confirmCampaignDraft(Long draftId) {
        if (draftId == null) {
            return Result.fail("草稿id不能为空");
        }
        AgentCampaignDraft draft = campaignDraftService.getById(draftId);
        if (draft == null) {
            return Result.fail("活动草稿不存在");
        }
        if (draft.getStatus() != null && draft.getStatus() != 1) {
            return Result.fail("活动草稿不是待确认状态，不能重复创建");
        }
        if (!merchantService.hasCurrentUserShopPermission(draft.getShopId())) {
            return Result.fail("无权管理该店铺");
        }
        Result validateResult = validateDraftBeforeConfirm(draft);
        if (!validateResult.getSuccess()) {
            return validateResult;
        }

        Voucher voucher = voucherAgentTool.createVoucherFromDraft(draft);

        campaignDraftService.updateById(new AgentCampaignDraft()
                .setId(draft.getId())
                .setStatus(2));
        draft.setStatus(2);
        agentSuggestionService.updateById(new AgentSuggestion()
                .setId(draft.getSuggestionId())
                .setStatus(4));
        registerSeckillCacheInitAfterCommit(draft, voucher);

        Map<String, Object> result = voucherAgentTool.draftToMap(draft);
        result.put("voucherId", voucher.getId());
        result.put("voucherIdText", String.valueOf(voucher.getId()));
        result.put("needConfirm", false);
        result.put("confirmed", true);
        result.put("humanInLoopMessage", "商家已确认，后端已把草稿转换为真实优惠券");
        if ("seckill".equals(draft.getDraftType())) {
            result.put("redisStockKey", SECKILL_STOCK_KEY + voucher.getId());
            result.put("redisBeginKey", SECKILL_BEGIN_KEY + voucher.getId());
            result.put("redisEndKey", SECKILL_END_KEY + voucher.getId());
        }
        result.put("message", "活动创建成功");
        AgentSuggestion suggestion = agentSuggestionService.getById(draft.getSuggestionId());
        Long sessionId = suggestion == null ? null : suggestion.getSessionId();
        recordAction(sessionId, draft.getShopId(), currentMerchantId(),
                "confirm_campaign_draft", "voucher", voucher.getId(), result);
        return Result.ok(result);
    }

    @Override
    @Transactional
    public Result chatWithAgent(Long shopId, MerchantAgentChatRequest request) {
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        if (request == null || isBlank(request.getMessage())) {
            return Result.fail("请输入要咨询 Agent 的问题");
        }
        Shop shop = shopAgentTool.getShop(shopId);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(shopId)) {
            return Result.fail("无权管理该店铺");
        }

        String userMessage = request.getMessage().trim();
        String intent = resolveChatIntent(userMessage);
        DateRange range = resolveDateRange(resolveChatDateRange(userMessage, request.getDateRange()));
        String toolName = resolveChatToolName(intent);
        List<Map<String, Object>> ragKnowledge = retrieveRagKnowledge(shopId, intent, userMessage);
        List<AgentMemoryPromptDTO> memories = agentMemoryService.listPromptMemories(shopId);
        String merchantMemory = agentMemoryService.buildMemoryPrompt(memories);
        AgentPromptContextDTO promptContext = buildPromptContext(shop, userMessage, intent, toolName, range, ragKnowledge,
                merchantMemory, memories.size());
        List<AgentFlowStepDTO> flowTrace = new ArrayList<>();
        flowTrace.add(buildFlowStep("receive_message", "接收商家问题", "success",
                "收到商家输入：" + userMessage, null, null));
        flowTrace.add(buildFlowStep("retrieve_knowledge", "检索运营知识", "success",
                ragKnowledge.isEmpty()
                        ? "未召回知识文档，本轮只基于业务数据回答"
                        : "已召回 " + ragKnowledge.size() + " 条运营知识",
                "knowledge_retrieval", null));
        flowTrace.add(buildFlowStep("understand_intent", "识别业务意图", "success",
                "识别为：" + resolveChatIntentName(intent), null, null));
        flowTrace.add(buildFlowStep("select_tool", "选择 Agent 工具", "success",
                "选择工具：" + toolName, toolName, null));
        AgentContext context = shouldUseLightweightContext(intent)
                ? buildLightweightAgentContext(shop)
                : buildAgentContext(shop, range);

        Long merchantId = currentMerchantId();
        AgentSession session = resolveChatSession(shopId, merchantId, userMessage, intent, request);
        if (session == null) {
            return Result.fail("会话不存在或不属于当前店铺");
        }
        Long sessionId = session.getId();
        Long workflowRunId = agentWorkflowRecorderService.startRun(sessionId, shopId, merchantId,
                "agent_chat", "merchant_message", userMessage, intent);
        int workflowStepOrder = 1;
        workflowStepOrder = recordWorkflowFlowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                flowTrace.get(0), "RECEIVE_MESSAGE", request, null);
        workflowStepOrder = recordWorkflowFlowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                flowTrace.get(1), "RAG_RETRIEVE", userMessage, ragKnowledge);
        workflowStepOrder = recordMemoryLoadStep(workflowRunId, sessionId, shopId, workflowStepOrder, memories);
        workflowStepOrder = recordWorkflowFlowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                flowTrace.get(2), "INTENT_RESOLVE", userMessage, intent);
            workflowStepOrder = recordWorkflowFlowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                    flowTrace.get(3), "TOOL_SELECT", intent, toolName);
            SkillContext skillContext = new SkillContext()
                    .setShopId(shopId)
                    .setUserId(merchantId)
                    .setSessionId(String.valueOf(sessionId))
                    .setWorkflowRunId(workflowRunId)
                    .setTraceId("agent-chat-" + sessionId + "-" + System.currentTimeMillis())
                    .setUserInput(userMessage)
                    .putAttribute("skillStepOrder", workflowStepOrder);

            // 对话链路先保存用户消息，再保存工具结果和助手回复，后续接大模型时可作为上下文记忆。
            try {
                saveMessage(sessionId, shopId, "user", userMessage, null, null, null);
                Map<String, Object> toolArgs = buildChatToolArgs(shopId, intent, range);
                Map<String, Object> toolResult = buildChatToolResult(context, intent, range);
                SkillResult<?> diagnosisSkillResult = executeDiagnosisSkillIfNeeded(intent, shopId, range, userMessage, skillContext);
                if (diagnosisSkillResult != null) {
                    toolResult.put("skillResult", diagnosisSkillResult);
                    toolResult.put("skillOutput", diagnosisSkillResult.getOutput());
                    flowTrace.add(buildFlowStep("execute_skill", "执行 Agent Skill",
                            diagnosisSkillResult.isSuccess() ? "success" : "failed",
                            diagnosisSkillResult.isSuccess()
                                    ? "已通过 SkillExecutionService 执行业务编排：" + diagnosisSkillResult.getMetadata().get("skillName")
                                    : diagnosisSkillResult.getErrorMessage(),
                            String.valueOf(diagnosisSkillResult.getMetadata().get("skillName")), null));
                    workflowStepOrder += 2;
                }
                AgentToolExecutionResultDTO toolExecution = agentToolExecutor.wrapResult(
                        toolName, toolArgs, toolResult);
            AgentFlowStepDTO toolStep = buildFlowStep("execute_tool", "执行工具并读取数据",
                    Boolean.TRUE.equals(toolExecution.getSuccess()) ? "success" : "failed",
                    Boolean.TRUE.equals(toolExecution.getSuccess())
                            ? "工具执行成功，已生成结构化工具结果"
                            : toolExecution.getErrorMsg(),
                    toolExecution.getToolName(), toolExecution.getCostMillis());
            flowTrace.add(toolStep);
            workflowStepOrder = recordWorkflowFlowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                    toolStep, "TOOL_EXECUTE", toolArgs, toolResult);
            saveMessage(sessionId, shopId, "tool", "已调用 " + toolExecution.getToolName(), toolExecution.getToolName(),
                    toolExecution.getToolArgs(), toSimpleJson(toolExecution));

            AgentRecommendationDTO recommendation = selectRecommendationByIntent(context.getRecommendations(), intent);
            Long suggestionId = null;
            AgentCampaignDraft draft = null;
            if (shouldSaveSuggestion(intent)) {
                suggestionId = saveChatSuggestion(sessionId, shopId, intent, recommendation, context);
                flowTrace.add(buildFlowStep("create_suggestion", "生成运营建议", "success",
                        "已生成可追踪的 Agent 建议：" + recommendation.getTitle(), null, null));
            } else {
                flowTrace.add(buildFlowStep("create_suggestion", "生成运营建议", "skipped",
                        "订单分析类问题只返回分析结果，不额外创建建议卡片", null, null));
            }
            AgentFlowStepDTO draftStep;
            if (suggestionId != null && shouldAutoCreateDraft(userMessage, request, intent)) {
                skillContext.putAttribute("skillStepOrder", workflowStepOrder);
                SkillResult<CouponDraftSkillOutput> draftSkillResult = executeCouponDraftSkill(shopId, range, userMessage,
                        recommendation, skillContext);
                workflowStepOrder += 2;
                if (draftSkillResult.isSuccess() && draftSkillResult.getOutput() != null
                        && draftSkillResult.getOutput().getDraftId() != null) {
                    draft = campaignDraftService.getById(draftSkillResult.getOutput().getDraftId());
                    if (draftSkillResult.getOutput().getSuggestionId() != null) {
                        suggestionId = draftSkillResult.getOutput().getSuggestionId();
                    }
                    draftStep = buildFlowStep("create_draft", "生成活动草稿", "success",
                            "已通过 CouponDraftSkill 生成待商家确认的活动草稿", "coupon_draft_skill", null);
                    toolResult.put("couponDraftSkill", draftSkillResult);
                } else {
                    draftStep = buildFlowStep("create_draft", "生成活动草稿", "failed",
                            draftSkillResult.getErrorMessage(), "coupon_draft_skill", null);
                    toolResult.put("couponDraftSkill", draftSkillResult);
                }
            } else {
                draftStep = buildFlowStep("create_draft", "生成活动草稿", "skipped",
                        "本轮未触发自动生成草稿，真实活动仍需商家确认", "voucher_campaign_tool", null);
            }
            flowTrace.add(draftStep);
            workflowStepOrder = recordWorkflowFlowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                    draftStep, "DRAFT_GENERATE", suggestionId, draft == null ? null : voucherAgentTool.draftToMap(draft));

            AgentModelResponseDTO modelResponse = merchantAgentModelClient.generateReply(new AgentModelRequestDTO()
                    .setPromptContext(promptContext)
                    .setToolExecution(toolExecution)
                    .setRecommendation(recommendation)
                    .setDraft(draft));
            recordModelCall(sessionId, shopId, merchantId, promptContext, toolExecution, modelResponse);
            workflowStepOrder = recordWorkflowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                    "model_call", "模型调用", "MODEL_CALL", null, "success",
                    promptContext, modelResponse, "已完成模型回复生成", modelResponse.getCostMillis());
            String reply = modelResponse.getReply();
            AgentFlowStepDTO replyStep = buildFlowStep("generate_reply", "生成回复", "success",
                    "已通过模型客户端 " + modelResponse.getProvider() + " 生成回复", null, null);
            flowTrace.add(replyStep);
            recordWorkflowFlowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                    replyStep, "FINAL_ANSWER", null, reply);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionId", String.valueOf(sessionId));
            result.put("shopId", shopId);
            result.put("intent", intent);
            result.put("intentName", resolveChatIntentName(intent));
            result.put("reply", reply);
            result.put("suggestionId", suggestionId == null ? null : String.valueOf(suggestionId));
            result.put("draftId", draft == null ? null : String.valueOf(draft.getId()));
            result.put("draft", draft == null ? null : voucherAgentTool.draftToMap(draft));
            result.put("toolResult", toolResult);
            result.put("toolExecution", toolExecution);
            result.put("promptContext", promptContext);
            result.put("flowTrace", flowTrace);
            result.put("modelResponse", modelResponse);

            saveMessage(sessionId, shopId, "assistant", reply, null, null, toSimpleJson(result));
            recordAction(sessionId, shopId, merchantId, "agent_chat", "session", sessionId, result);
            agentWorkflowRecorderService.finishRun(workflowRunId, reply);
            return Result.ok(result);
        } catch (RuntimeException e) {
            agentWorkflowRecorderService.failRun(workflowRunId, e.getMessage());
            throw e;
        }
    }

    @Override
    @Transactional
    public Result toolChatWithAgent(Long shopId, MerchantAgentChatRequest request) {
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        if (request == null || isBlank(request.getMessage())) {
            return Result.fail("请输入要咨询 Agent 的问题");
        }
        Shop shop = shopAgentTool.getShop(shopId);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        if (!merchantService.hasCurrentUserShopPermission(shopId)) {
            return Result.fail("无权管理该店铺");
        }

        Long merchantId = currentMerchantId();
        String userMessage = request.getMessage().trim();
        AgentSession session = resolveChatSession(shopId, merchantId, userMessage, "tool_calling_chat", request);
        if (session == null) {
            return Result.fail("会话不存在或不属于当前店铺");
        }
        Long sessionId = session.getId();
        List<AgentMessage> historyMessages = loadRecentNaturalMessages(sessionId);
        List<AgentMemoryPromptDTO> memories = agentMemoryService.listPromptMemories(shopId);
        String merchantMemory = agentMemoryService.buildMemoryPrompt(memories);
        saveMessage(sessionId, shopId, "user", userMessage, null, null, null);
        Long workflowRunId = agentWorkflowRecorderService.startRun(sessionId, shopId, merchantId,
                "tool_calling_chat", "merchant_message", userMessage, "tool_calling_chat");
        int workflowStepOrder = recordMemoryLoadStep(workflowRunId, sessionId, shopId, 1, memories);
        workflowStepOrder = recordWorkflowStep(workflowRunId, sessionId, shopId, workflowStepOrder,
                "tool_calling_start", "开始 Tool Calling", "TOOL_CALLING_START", null, "success",
                request, null, "已开始 Tool Calling 对话", null);

        try {
            Map<String, Object> toolCallingResult = toolCallingService.chat(shop, request, historyMessages, merchantMemory, memories);
            String reply = String.valueOf(toolCallingResult.getOrDefault("reply", toolCallingResult.get("answer")));
            toolCallingResult.put("sessionId", String.valueOf(sessionId));
            toolCallingResult.put("shopId", shopId);
            toolCallingResult.put("scene", "tool_calling_chat");
            workflowStepOrder = recordToolCallingWorkflowSteps(workflowRunId, sessionId, shopId,
                    workflowStepOrder, toolCallingResult);

            saveMessage(sessionId, shopId, "assistant", reply, null, null, toSimpleJson(toolCallingResult));
            recordToolCallingModelAction(sessionId, shopId, merchantId, request, toolCallingResult);
            recordToolCallingToolActions(sessionId, shopId, merchantId, toolCallingResult);
            recordAction(sessionId, shopId, merchantId, "tool_calling_chat", "session", sessionId, toolCallingResult);
            agentWorkflowRecorderService.finishRun(workflowRunId, reply);
            return Result.ok(toolCallingResult);
        } catch (Exception e) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("message", request.getMessage());
            errorResult.put("error", e.getMessage());
            recordFailedAction(sessionId, shopId, merchantId, "tool_calling_chat", "session", sessionId, errorResult, e.getMessage());
            agentWorkflowRecorderService.failRun(workflowRunId, e.getMessage());
            return Result.fail("Tool Calling 调用失败：" + e.getMessage());
        }
    }

    private AgentPromptContextDTO buildPromptContext(Shop shop, String userMessage, String intent,
                                                     String toolName, DateRange range,
                                                     List<Map<String, Object>> ragKnowledge,
                                                     String merchantMemory, Integer memoryHitCount) {
        List<String> constraints = new ArrayList<>();
        constraints.add("先判断商家问题类型，再决定是否需要经营数据分析");
        constraints.add("身份、模型、能力说明类问题不要展开订单或店铺经营报告");
        constraints.add("只能基于当前店铺数据、订单数据、优惠券数据和评价数据给出建议");
        constraints.add("如果检索到运营知识，必须优先结合知识库规则说明建议依据");
        constraints.add("涉及创建真实优惠券或秒杀券时，必须先生成草稿，由商家确认后才能写入业务表");
        constraints.add("回复要说明关键指标和下一步动作，避免只给空泛建议");
        constraints.add("如果数据不足，要明确提示数据不足，而不是编造结论");

        return new AgentPromptContextDTO()
                .setScene("merchant_operation_chat")
                .setSystemPrompt(promptTemplateService.systemPrompt())
                .setUserMessage(userMessage)
                .setIntent(intent)
                .setIntentName(resolveChatIntentName(intent))
                .setSelectedToolName(toolName)
                .setDateRange(range.getCode())
                .setShopId(shop.getId())
                .setShopName(shop.getName())
                .setConstraints(constraints)
                .setOutputFormat(promptTemplateService.outputRequirement(intent))
                .setRagKnowledge(ragKnowledge)
                .setRagRetrievalMode(resolveRagRetrievalMode(ragKnowledge))
                .setMerchantMemory(merchantMemory)
                .setMemoryHitCount(memoryHitCount == null ? 0 : memoryHitCount);
    }

    private AgentFlowStepDTO buildFlowStep(String stepCode, String stepName, String status,
                                           String detail, String toolName, Long costMillis) {
        return new AgentFlowStepDTO()
                .setStepCode(stepCode)
                .setStepName(stepName)
                .setStatus(status)
                .setDetail(detail)
                .setToolName(toolName)
                .setCostMillis(costMillis);
    }

    private int recordWorkflowFlowStep(Long runId, Long sessionId, Long shopId, int stepOrder,
                                       AgentFlowStepDTO step, String nodeType, Object input, Object output) {
        if (step == null) {
            return stepOrder;
        }
        return recordWorkflowStep(runId, sessionId, shopId, stepOrder,
                step.getStepCode(), step.getStepName(), nodeType, step.getToolName(), step.getStatus(),
                input, output, step.getDetail(), step.getCostMillis());
    }

    private int recordWorkflowStep(Long runId, Long sessionId, Long shopId, int stepOrder,
                                   String stepCode, String stepName, String nodeType, String toolName,
                                   String status, Object input, Object output, String detail, Long costMillis) {
        agentWorkflowRecorderService.recordStep(runId, sessionId, shopId, stepOrder,
                stepCode, stepName, nodeType, toolName, status, input, output, detail, null, costMillis);
        return stepOrder + 1;
    }

    private int recordMemoryLoadStep(Long runId, Long sessionId, Long shopId, int stepOrder,
                                     List<AgentMemoryPromptDTO> memories) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("shopId", shopId);
        input.put("status", 1);
        input.put("memoryTypes", java.util.Arrays.asList("PREFERENCE", "CONSTRAINT"));
        return recordWorkflowStep(runId, sessionId, shopId, stepOrder,
                "MEMORY_LOAD", "加载商家记忆", "MEMORY_LOAD", null, "success",
                input, agentMemoryService.buildMemoryLoadSummary(memories),
                memories == null || memories.isEmpty()
                        ? "本轮未命中启用商家记忆"
                        : "本轮命中 " + memories.size() + " 条启用商家记忆",
                null);
    }

    private int recordToolCallingWorkflowSteps(Long runId, Long sessionId, Long shopId, int stepOrder,
                                               Map<String, Object> toolCallingResult) {
        List<Object> flowSteps = asList(toolCallingResult.get("flowTrace"));
        Map<String, Object> promptContext = asMap(toolCallingResult.get("promptContext"));
        Object ragKnowledge = promptContext.get("ragKnowledge");
        stepOrder = recordWorkflowStep(runId, sessionId, shopId, stepOrder,
                "retrieve_knowledge", "检索运营知识", "RAG_RETRIEVE", "knowledge_retrieval", "success",
                promptContext.get("userMessage"), ragKnowledge,
                resolveFlowDetail(flowSteps, "retrieve_knowledge", "已完成 Tool Calling RAG 检索"), null);
        stepOrder = recordWorkflowStep(runId, sessionId, shopId, stepOrder,
                "tool_spec_build", "构建工具白名单", "TOOL_SPEC_BUILD", null, "success",
                null, toolCallingResult.get("calledTools"), "已基于 AgentToolRegistry 构建模型可调用只读工具列表", null);
        stepOrder = recordWorkflowStep(runId, sessionId, shopId, stepOrder,
                "select_tool", "模型选择工具", "MODEL_TOOL_DECISION", null,
                resolveFlowStatus(flowSteps, "select_tool", "success"),
                promptContext, toolCallingResult.get("calledTools"),
                resolveFlowDetail(flowSteps, "select_tool", "模型已完成工具选择"), null);

        List<Object> toolCalls = asList(toolCallingResult.get("toolCalls"));
        for (Object item : toolCalls) {
            Map<String, Object> toolCall = asMap(item);
            if (toolCall.isEmpty()) {
                continue;
            }
            boolean success = Boolean.TRUE.equals(toolCall.get("success"));
            stepOrder = recordWorkflowStep(runId, sessionId, shopId, stepOrder,
                    "execute_tool", "执行只读工具", "READONLY_TOOL_EXECUTE",
                    String.valueOf(toolCall.get("toolName")),
                    success ? "success" : "failed",
                    toolCall.get("arguments"), toolCall.get("result"),
                    success ? "已执行只读工具" : "只读工具执行失败",
                    longValue(toolCall.get("costMillis")));
        }
        recordWorkflowStep(runId, sessionId, shopId, stepOrder,
                "generate_reply", "生成最终回复", "FINAL_ANSWER", null, "success",
                toolCallingResult.get("calledTools"), toolCallingResult.get("reply"),
                resolveFlowDetail(flowSteps, "generate_reply", "已生成最终回复"),
                longValue(toolCallingResult.get("costMillis")));
        return stepOrder + 1;
    }

    private String resolveFlowDetail(List<Object> flowSteps, String stepCode, String defaultValue) {
        Map<String, Object> step = findFlowStep(flowSteps, stepCode);
        Object detail = step.get("detail");
        return detail == null ? defaultValue : String.valueOf(detail);
    }

    private String resolveFlowStatus(List<Object> flowSteps, String stepCode, String defaultValue) {
        Map<String, Object> step = findFlowStep(flowSteps, stepCode);
        Object status = step.get("status");
        return status == null ? defaultValue : String.valueOf(status);
    }

    private Map<String, Object> findFlowStep(List<Object> flowSteps, String stepCode) {
        for (Object item : flowSteps) {
            if (item instanceof AgentFlowStepDTO) {
                AgentFlowStepDTO step = (AgentFlowStepDTO) item;
                if (stepCode.equals(step.getStepCode())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("stepCode", step.getStepCode());
                    row.put("status", step.getStatus());
                    row.put("detail", step.getDetail());
                    return row;
                }
            }
            Map<String, Object> step = asMap(item);
            if (stepCode.equals(step.get("stepCode"))) {
                return step;
            }
        }
        return Collections.emptyMap();
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<Map<String, Object>> retrieveRagKnowledge(Long shopId, String intent, String userMessage) {
        try {
            return agentKnowledgeDocService.retrieveForAgentForShop(shopId, intent, userMessage, 3);
        } catch (Exception e) {
            // RAG 是增强能力，不能因为知识库检索失败导致主对话不可用。
            // 企业项目里这类增强链路通常要降级为空知识，并在日志中排查。
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private SkillResult<?> executeDiagnosisSkillIfNeeded(String intent, Long shopId, DateRange range,
                                                         String userMessage, SkillContext skillContext) {
        if (!"operation_chat".equals(intent)) {
            return null;
        }
        MerchantDiagnosisSkillInput input = new MerchantDiagnosisSkillInput()
                .setShopId(shopId)
                .setTimeRange(range == null ? null : range.getCode())
                .setUserQuestion(userMessage);
        return skillExecutionService.execute("merchant_diagnosis_skill", input, skillContext);
    }

    @SuppressWarnings("unchecked")
    private SkillResult<CouponDraftSkillOutput> executeCouponDraftSkill(Long shopId, DateRange range, String userMessage,
                                                                        AgentRecommendationDTO recommendation,
                                                                        SkillContext skillContext) {
        CouponDraftSkillInput input = new CouponDraftSkillInput()
                .setShopId(shopId)
                .setCampaignGoal(recommendation == null || isBlank(recommendation.getTitle())
                        ? "生成优惠券活动草稿"
                        : recommendation.getTitle())
                .setUserRequirement(userMessage)
                .setTimeRange(range == null ? null : range.getCode())
                .setDraftType(resolveDraftTypeForSkill(userMessage, recommendation));
        SkillResult<?> result = skillExecutionService.execute("coupon_draft_skill", input, skillContext);
        return (SkillResult<CouponDraftSkillOutput>) result;
    }

    private String resolveDraftTypeForSkill(String userMessage, AgentRecommendationDTO recommendation) {
        String text = (userMessage == null ? "" : userMessage)
                + " "
                + (recommendation == null ? "" : recommendation.getType())
                + " "
                + (recommendation == null ? "" : recommendation.getTitle());
        if (containsAny(text, "秒杀", "seckill")) {
            return "seckill";
        }
        return "voucher";
    }

    private String resolveRagRetrievalMode(List<Map<String, Object>> ragKnowledge) {
        if (ragKnowledge == null || ragKnowledge.isEmpty()) {
            return "skipped_or_no_hit";
        }
        Object mode = ragKnowledge.get(0).get("retrievalMode");
        return mode == null ? "unknown" : String.valueOf(mode);
    }

    private AgentContext buildAgentContext(Shop shop, DateRange range) {
        List<Voucher> vouchers = voucherAgentTool.queryShopVouchers(shop.getId());
        List<Long> voucherIds = vouchers.stream().map(Voucher::getId).collect(Collectors.toList());
        Map<Long, Voucher> voucherMap = vouchers.stream().collect(Collectors.toMap(Voucher::getId, voucher -> voucher));
        List<VoucherOrder> orders = orderAgentTool.queryOrders(voucherIds, range.getStartTime());
        List<SeckillVoucher> seckillVouchers = voucherAgentTool.querySeckillVouchers(voucherIds);
        List<Blog> blogs = reviewAgentTool.queryShopBlogs(shop.getId());
        List<BlogComments> comments = reviewAgentTool.queryComments(blogs);

        AgentContext context = new AgentContext();
        context.setShop(shop);
        context.setShopProfile(shopAgentTool.buildShopProfile(shop));
        context.setOrderAnalysis(orderAgentTool.buildOrderAnalysis(orders, voucherMap));
        context.setVoucherAnalysis(voucherAgentTool.buildVoucherAnalysis(vouchers, seckillVouchers));
        context.setReviewAnalysis(reviewAgentTool.buildReviewAnalysis(blogs, comments));
        context.setRecommendations(buildRecommendations(shop, orders, vouchers, seckillVouchers, blogs, comments));
        return context;
    }

    private AgentContext buildLightweightAgentContext(Shop shop) {
        AgentContext context = new AgentContext();
        context.setShop(shop);
        context.setShopProfile(shopAgentTool.buildShopProfile(shop));
        context.setRecommendations(Collections.emptyList());
        return context;
    }

    private String resolveChatIntent(String message) {
        return rulePolicyService.resolveIntent(message);
    }

    private String resolveChatDateRange(String message, String requestRange) {
        if (!isBlank(requestRange)) {
            return requestRange;
        }
        if (message != null && (message.contains("今天") || message.contains("今日"))) {
            return "TODAY";
        }
        if (message != null && (message.contains("7天") || message.contains("一周") || message.contains("近周"))) {
            return "LAST_7_DAYS";
        }
        return "LAST_30_DAYS";
    }

    private String buildChatSessionTitle(String message) {
        String title = message.trim();
        if (title.length() > 18) {
            return title.substring(0, 18) + "...";
        }
        return title;
    }

    private Map<String, Object> buildChatToolArgs(Long shopId, String intent, DateRange range) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("shopId", shopId);
        args.put("intent", intent);
        args.put("dateRange", range.getCode());
        args.put("startTime", range.getStartTime());
        args.put("endTime", range.getEndTime());
        return args;
    }

    private Map<String, Object> buildChatToolResult(AgentContext context, String intent, DateRange range) {
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("dateRange", range.getCode());
        toolResult.put("shopProfile", context.getShopProfile());
        if ("identity".equals(intent)) {
            toolResult.put("agentProfile", buildAgentProfile());
            return toolResult;
        }
        if ("order_analysis".equals(intent)) {
            toolResult.put("orderAnalysis", context.getOrderAnalysis());
            return toolResult;
        }
        if ("voucher_plan".equals(intent)) {
            toolResult.put("voucherAnalysis", context.getVoucherAnalysis());
            toolResult.put("recommendations", context.getRecommendations());
            return toolResult;
        }
        if ("review_analysis".equals(intent)) {
            toolResult.put("reviewAnalysis", context.getReviewAnalysis());
            toolResult.put("recommendations", context.getRecommendations());
            return toolResult;
        }
        toolResult.put("orderAnalysis", context.getOrderAnalysis());
        toolResult.put("voucherAnalysis", context.getVoucherAnalysis());
        toolResult.put("reviewAnalysis", context.getReviewAnalysis());
        toolResult.put("recommendations", context.getRecommendations());
        return toolResult;
    }

    private AgentRecommendationDTO selectRecommendationByIntent(List<AgentRecommendationDTO> recommendations, String intent) {
        if ("identity".equals(intent)) {
            return null;
        }
        if (recommendations == null || recommendations.isEmpty()) {
            return buildRecommendation("operation", "继续观察经营数据",
                    "当前数据暂未触发强规则建议。",
                    "建议先生成完整运营报告，观察订单、券和评价三类指标。",
                    3, 1, "保持经营动作可追踪，为后续 Agent 分析积累数据。");
        }
        if ("voucher_plan".equals(intent)) {
            for (AgentRecommendationDTO recommendation : recommendations) {
                if (containsAny(recommendation.getType(), "seckill", "voucher", "member")) {
                    return recommendation;
                }
            }
        }
        if ("review_analysis".equals(intent)) {
            for (AgentRecommendationDTO recommendation : recommendations) {
                if (containsAny(recommendation.getType(), "review", "content", "shop")) {
                    return recommendation;
                }
            }
        }
        return recommendations.get(0);
    }

    private boolean shouldSaveSuggestion(String intent) {
        return "voucher_plan".equals(intent)
                || "review_analysis".equals(intent)
                || "operation_chat".equals(intent);
    }

    private Long saveChatSuggestion(Long sessionId, Long shopId, String intent,
                                    AgentRecommendationDTO recommendation, AgentContext context) {
        AgentSuggestion suggestion = new AgentSuggestion()
                .setId(nextAgentId())
                .setSessionId(sessionId)
                .setShopId(shopId)
                .setSuggestionType(recommendation.getType() == null ? intent : recommendation.getType())
                .setTitle(recommendation.getTitle())
                .setSummary(recommendation.getReason())
                .setContent(recommendation.getAction() + " 预期效果：" + recommendation.getExpectedEffect())
                .setConfidenceScore(new BigDecimal("78.00"))
                .setRiskLevel(recommendation.getRiskLevel() == null ? 1 : recommendation.getRiskLevel())
                .setStatus(1);
        agentSuggestionService.save(suggestion);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionId", String.valueOf(suggestion.getId()));
        result.put("recommendation", recommendation);
        result.put("shopName", context.getShop().getName());
        recordAction(sessionId, shopId, currentMerchantId(), "create_chat_suggestion",
                "suggestion", suggestion.getId(), result);
        return suggestion.getId();
    }

    private boolean shouldAutoCreateDraft(String userMessage, MerchantAgentChatRequest request, String intent) {
        if (!"voucher_plan".equals(intent)) {
            return false;
        }
        if (request.getAutoDraft() != null) {
            return request.getAutoDraft();
        }
        return containsAny(userMessage, "生成", "设计", "草稿", "秒杀券", "代金券", "优惠券", "活动");
    }

    private AgentCampaignDraft createDraftFromChatSuggestion(Long suggestionId, AgentRecommendationDTO recommendation, Shop shop) {
        AgentSuggestion suggestion = agentSuggestionService.getById(suggestionId);
        MerchantCampaignDraftRequest draftRequest = new MerchantCampaignDraftRequest();
        draftRequest.setRecommendationType(recommendation.getType());
        draftRequest.setRecommendationTitle(recommendation.getTitle());
        draftRequest.setRecommendationReason(recommendation.getReason());
        draftRequest.setRecommendationAction(recommendation.getAction());
        AgentCampaignDraft draft = voucherAgentTool.buildCampaignDraft(suggestion, shop, draftRequest, nextAgentId());
        campaignDraftService.save(draft);
        agentSuggestionService.updateById(new AgentSuggestion()
                .setId(suggestionId)
                .setStatus(2));
        recordAction(suggestion.getSessionId(), shop.getId(), currentMerchantId(),
                "create_campaign_draft", "draft", draft.getId(), voucherAgentTool.draftToMap(draft));
        return draft;
    }

    private String buildChatReply(String intent, AgentContext context,
                                  AgentRecommendationDTO recommendation, AgentCampaignDraft draft) {
        Shop shop = context.getShop();
        OrderStatsDTO order = context.getOrderAnalysis();
        VoucherStatsDTO voucher = context.getVoucherAnalysis();
        ReviewStatsDTO review = context.getReviewAnalysis();
        if ("order_analysis".equals(intent)) {
            return shop.getName() + "当前周期共有券订单" + order.getTotalOrders()
                    + "笔，已支付" + order.getPaidOrders()
                    + "笔，预计收入" + formatFen(order.getEstimatedRevenue())
                    + "，支付转化率" + order.getConversionRate()
                    + "。热门券是：" + order.getTopVoucher() + "。";
        }
        if ("voucher_plan".equals(intent)) {
            String reply = "我建议采用【" + recommendation.getTitle() + "】。原因是："
                    + recommendation.getReason() + " " + recommendation.getAction()
                    + " 当前店铺在线券" + voucher.getOnlineVouchers()
                    + "张，秒杀库存" + voucher.getSeckillStock() + "。";
            if (draft != null) {
                reply += " 我已经生成待确认活动草稿，商家可以继续编辑后确认创建。";
            }
            return reply;
        }
        if ("review_analysis".equals(intent)) {
            return "内容侧当前有探店笔记" + review.getBlogCount()
                    + "篇，评论互动" + review.getCommentCount()
                    + "次，互动等级为" + review.getEngagementLevel()
                    + "。建议：" + recommendation.getAction();
        }
        return shop.getName() + "当前周期订单" + order.getTotalOrders()
                + "笔，在线优惠券" + voucher.getOnlineVouchers()
                + "张，内容互动等级" + review.getEngagementLevel()
                + "。优先建议：" + recommendation.getTitle() + "，" + recommendation.getAction();
    }

    private String resolveChatToolName(String intent) {
        return rulePolicyService.resolveToolName(intent);
    }

    private String resolveChatIntentName(String intent) {
        if ("identity".equals(intent)) {
            return "身份能力说明";
        }
        if ("order_analysis".equals(intent)) {
            return "订单分析";
        }
        if ("voucher_plan".equals(intent)) {
            return "活动方案";
        }
        if ("review_analysis".equals(intent)) {
            return "评价内容分析";
        }
        return "综合运营咨询";
    }

    private boolean shouldUseLightweightContext(String intent) {
        return "identity".equals(intent);
    }

    private Map<String, Object> buildAgentProfile() {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "黑马点评商家运营 Agent");
        profile.put("position", "本地生活商家运营助手");
        profile.put("modelProvider", "LangChain4j + DashScope Qwen，异常时自动降级规则版");
        profile.put("capabilities", java.util.Arrays.asList(
                "分析店铺订单、支付转化和预计收入",
                "诊断优惠券和秒杀券投放结构",
                "分析评价、探店内容和用户互动",
                "生成需要商家确认的优惠券或秒杀券草稿",
                "记录工具调用流程和操作审计"
        ));
        profile.put("safetyRule", "不会直接创建真实活动，必须先生成草稿并由商家确认");
        return profile;
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private int textLength(String value) {
        return value == null ? 0 : value.trim().length();
    }

    private String formatFen(Long value) {
        long fen = value == null ? 0L : value;
        return "¥" + (fen / 100) + "." + String.format("%02d", Math.abs(fen % 100));
    }

    /**
     * 秒杀券确认成功后初始化 Redis 热点数据。
     *
     * <p>这里注册 afterCommit 回调，只有 MySQL 事务提交成功后才写 Redis。
     * 这样能避免“数据库回滚了，但 Redis 已经有库存”的缓存脏数据问题。</p>
     */
    private void registerSeckillCacheInitAfterCommit(AgentCampaignDraft draft, Voucher voucher) {
        if (!"seckill".equals(draft.getDraftType())) {
            return;
        }
        Runnable initCacheTask = () -> initSeckillVoucherRedisCache(voucher.getId(), draft);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    initCacheTask.run();
                }
            });
            return;
        }
        initCacheTask.run();
    }

    /**
     * 写入秒杀 Lua 脚本需要读取的 Redis key。
     */
    private void initSeckillVoucherRedisCache(Long voucherId, AgentCampaignDraft draft) {
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, String.valueOf(draft.getStock()));
        stringRedisTemplate.opsForValue().set(SECKILL_BEGIN_KEY + voucherId, String.valueOf(toEpochMilli(draft.getBeginTime())));
        stringRedisTemplate.opsForValue().set(SECKILL_END_KEY + voucherId, String.valueOf(toEpochMilli(draft.getEndTime())));
    }

    private long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private Map<Long, Integer> countMessagesBySession(List<Long> sessionIds) {
        Map<Long, Integer> result = new HashMap<>();
        List<AgentMessage> messages = agentMessageService.query()
                .in("session_id", sessionIds)
                .list();
        for (AgentMessage message : messages) {
            result.put(message.getSessionId(), result.getOrDefault(message.getSessionId(), 0) + 1);
        }
        return result;
    }

    private Map<Long, AgentSuggestion> queryLatestSuggestionBySession(List<Long> sessionIds) {
        Map<Long, AgentSuggestion> result = new HashMap<>();
        List<AgentSuggestion> suggestions = agentSuggestionService.query()
                .in("session_id", sessionIds)
                .orderByDesc("create_time")
                .list();
        for (AgentSuggestion suggestion : suggestions) {
            // 查询结果已经按创建时间倒序排列，第一次出现的就是该会话最新建议。
            result.putIfAbsent(suggestion.getSessionId(), suggestion);
        }
        return result;
    }

    private List<AgentRecommendationDTO> buildRecommendations(Shop shop, List<VoucherOrder> orders,
                                                              List<Voucher> vouchers,
                                                              List<SeckillVoucher> seckillVouchers,
                                                              List<Blog> blogs,
                                                              List<BlogComments> comments) {
        List<AgentRecommendationDTO> recommendations = new ArrayList<>();
        if (vouchers.isEmpty()) {
            recommendations.add(buildRecommendation(
                    "voucher",
                    "创建低风险普通代金券",
                    "当前店铺没有在线优惠券，缺少基础转化工具。",
                    "建议先创建一张低门槛、低库存的普通代金券，用于验证用户转化。",
                    1,
                    1,
                    "帮助店铺获得第一批券订单数据，方便后续判断活动效果。"
            ));
        }

        if (orders.size() < 10) {
            recommendations.add(buildRecommendation(
                    "seckill",
                    "投放小库存秒杀券",
                    "近周期券订单量偏少，说明当前优惠活动对用户吸引力不足。",
                    "建议选择晚间或周末时段，投放一张小库存秒杀券提升曝光。",
                    1,
                    2,
                    "提升短期点击和下单转化，同时通过小库存控制成本风险。"
            ));
        }

        if (seckillVouchers.isEmpty()) {
            recommendations.add(buildRecommendation(
                    "seckill",
                    "补充限时秒杀活动",
                    "当前店铺缺少秒杀券，无法利用限时优惠制造紧迫感。",
                    "可以为高峰时段设计一张限时券，库存建议先控制在 50-100 张。",
                    2,
                    2,
                    "增强用户下单动力，适合测试店铺在高峰期的活动承接能力。"
            ));
        }

        if (blogs.isEmpty()) {
            recommendations.add(buildRecommendation(
                    "content",
                    "补充探店内容",
                    "当前店铺探店内容不足，用户缺少消费前的参考信息。",
                    "建议邀请用户发布体验笔记，沉淀菜品、环境、服务等真实内容。",
                    2,
                    1,
                    "提升店铺内容可信度，增强新用户到店前的信任感。"
            ));
        }

        if (comments.isEmpty()) {
            recommendations.add(buildRecommendation(
                    "review",
                    "加强评价互动",
                    "当前评论互动数据不足，店铺服务亮点没有被持续沉淀。",
                    "建议商家主动回复评价，总结用户高频关注的问题和服务亮点。",
                    3,
                    1,
                    "提升用户感知到的服务温度，也为后续 Agent 分析提供更多文本数据。"
            ));
        }

        if (shop.getScore() != null && shop.getScore() < 40) {
            recommendations.add(buildRecommendation(
                    "shop",
                    "优先修复口碑问题",
                    "店铺评分偏低，直接放大投流或秒杀可能带来更多负面反馈。",
                    "短期不建议大幅降价冲量，应优先处理评价中的服务和体验问题。",
                    1,
                    3,
                    "降低活动放大负面口碑的风险，先改善基础体验再做增长。"
            ));
        }
        if (recommendations.isEmpty()) {
            recommendations.add(buildRecommendation(
                    "member",
                    "设计复购券或会员专享券",
                    "店铺基础运营数据较完整，可以从拉新转向复购运营。",
                    "建议设计复购券或会员专享券，面向已购买用户做二次触达。",
                    2,
                    1,
                    "提升老客回访率，让优惠从一次性促销变成长期运营工具。"
            ));
        }

        return recommendations;
    }

    private AgentRecommendationDTO buildRecommendation(String type, String title, String reason,
                                                       String action, Integer priority,
                                                       Integer riskLevel, String expectedEffect) {
        AgentRecommendationDTO recommendation = new AgentRecommendationDTO();
        recommendation.setType(type);
        recommendation.setTitle(title);
        recommendation.setReason(reason);
        recommendation.setAction(action);
        recommendation.setPriority(priority);
        recommendation.setRiskLevel(riskLevel);
        recommendation.setExpectedEffect(expectedEffect);
        return recommendation;
    }

    private String buildSummary(Shop shop, DateRange range, OrderStatsDTO orderAnalysis,
                                VoucherStatsDTO voucherAnalysis, ReviewStatsDTO reviewAnalysis,
                                List<AgentRecommendationDTO> recommendations) {
        AgentRecommendationDTO topRecommendation = recommendations.get(0);

        return shop.getName() + "在" + range.getLabel()
                + "内产生券订单" + orderAnalysis.getTotalOrders() + "笔，"
                + "已支付" + orderAnalysis.getPaidOrders() + "笔，"
                + "当前配置优惠券" + voucherAnalysis.getTotalVouchers() + "张，"
                + "探店内容" + reviewAnalysis.getBlogCount() + "篇，"
                + "内容互动等级为" + reviewAnalysis.getEngagementLevel() + "。"
                + "优先建议：" + topRecommendation.getTitle()
                + "，" + topRecommendation.getAction();
    }

    private Long saveOperationSuggestion(Long sessionId, Long shopId, String summary,
                                         List<AgentRecommendationDTO> recommendations) {
        AgentSuggestion suggestion = new AgentSuggestion()
                .setId(nextAgentId())
                .setSessionId(sessionId)
                .setShopId(shopId)
                .setSuggestionType("operation")
                .setTitle("店铺运营报告")
                .setSummary(summary)
                .setContent(buildSuggestionContent(recommendations))
                .setConfidenceScore(new BigDecimal("80.00"))
                .setRiskLevel(resolveOverallRiskLevel(recommendations))
                .setStatus(1);
        agentSuggestionService.save(suggestion);
        return suggestion.getId();
    }

    private Integer resolveOverallRiskLevel(List<AgentRecommendationDTO> recommendations) {
        int maxRiskLevel = 1;
        for (AgentRecommendationDTO recommendation : recommendations) {
            if (recommendation.getRiskLevel() != null && recommendation.getRiskLevel() > maxRiskLevel) {
                maxRiskLevel = recommendation.getRiskLevel();
            }
        }
        return maxRiskLevel;
    }

    private String buildSuggestionContent(List<AgentRecommendationDTO> recommendations) {
        List<String> lines = new ArrayList<>();
        for (AgentRecommendationDTO recommendation : recommendations) {
            lines.add("【" + recommendation.getTitle() + "】"
                    + recommendation.getReason()
                    + " " + recommendation.getAction()
                    + " 预期效果：" + recommendation.getExpectedEffect());
        }
        return String.join("\n", lines);
    }

    private void saveMessage(Long sessionId, Long shopId, String role, String content,
                             String toolName, String toolArgs, String toolResult) {
        AgentMessage message = new AgentMessage()
                .setId(nextAgentId())
                .setSessionId(sessionId)
                .setShopId(shopId)
                .setRole(role)
                .setContent(content)
                .setToolName(toolName)
                .setToolArgs(toolArgs)
                .setToolResult(toolResult);
        agentMessageService.save(message);
    }

    private AgentSession resolveChatSession(Long shopId, Long merchantId, String userMessage,
                                            String scene, MerchantAgentChatRequest request) {
        Long requestSessionId = resolveRequestSessionId(request);
        if (requestSessionId != null) {
            AgentSession existingSession = agentSessionService.getById(requestSessionId);
            if (existingSession == null || !shopId.equals(existingSession.getShopId())) {
                return null;
            }
            return existingSession;
        }
        AgentSession session = new AgentSession()
                .setId(nextAgentId())
                .setShopId(shopId)
                .setMerchantId(merchantId)
                .setTitle(buildChatSessionTitle(userMessage))
                .setScene(scene)
                .setStatus(2);
        agentSessionService.save(session);
        return session;
    }

    private Long resolveRequestSessionId(MerchantAgentChatRequest request) {
        if (request == null) {
            return null;
        }
        String sessionId = !isBlank(request.getSessionId()) ? request.getSessionId() : request.getConversationId();
        if (isBlank(sessionId)) {
            return null;
        }
        try {
            return Long.valueOf(sessionId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<AgentMessage> loadRecentNaturalMessages(Long sessionId) {
        if (sessionId == null) {
            return Collections.emptyList();
        }
        List<AgentMessage> messages = agentMessageService.query()
                .eq("session_id", sessionId)
                .in("role", "user", "assistant")
                .orderByAsc("create_time")
                .list();
        if (messages.size() <= 6) {
            return messages;
        }
        return messages.subList(messages.size() - 6, messages.size());
    }

    private void recordAction(Long sessionId, Long shopId, Long merchantId, String actionType,
                              String targetType, Long targetId, Map<String, Object> result) {
        AgentActionLog actionLog = new AgentActionLog()
                .setId(nextAgentId())
                .setSessionId(sessionId)
                .setShopId(shopId)
                .setOperatorId(merchantId)
                .setOperatorType("merchant")
                .setActionType(actionType)
                .setTargetType(targetType)
                .setTargetId(targetId)
                .setResultData(toSimpleJson(result))
                .setStatus(1);
        agentActionLogService.save(actionLog);
    }

    private void recordFailedAction(Long sessionId, Long shopId, Long merchantId, String actionType,
                                    String targetType, Long targetId, Map<String, Object> request,
                                    String errorMsg) {
        AgentActionLog actionLog = new AgentActionLog()
                .setId(nextAgentId())
                .setSessionId(sessionId)
                .setShopId(shopId)
                .setOperatorId(merchantId)
                .setOperatorType("agent")
                .setActionType(actionType)
                .setTargetType(targetType)
                .setTargetId(targetId)
                .setRequestData(toSimpleJson(request))
                .setStatus(2)
                .setErrorMsg(errorMsg);
        agentActionLogService.save(actionLog);
    }

    private void recordModelCall(Long sessionId, Long shopId, Long merchantId,
                                 AgentPromptContextDTO promptContext,
                                 AgentToolExecutionResultDTO toolExecution,
                                 AgentModelResponseDTO modelResponse) {
        Map<String, Object> requestData = new LinkedHashMap<>();
        requestData.put("intent", promptContext.getIntent());
        requestData.put("intentName", promptContext.getIntentName());
        requestData.put("selectedToolName", promptContext.getSelectedToolName());
        requestData.put("dateRange", promptContext.getDateRange());
        requestData.put("promptVersion", modelResponse.getPromptVersion());
        requestData.put("toolName", toolExecution == null ? null : toolExecution.getToolName());
        requestData.put("ragRetrievalMode", promptContext.getRagRetrievalMode());
        requestData.put("ragHitCount", promptContext.getRagKnowledge() == null ? 0 : promptContext.getRagKnowledge().size());

        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("provider", modelResponse.getProvider());
        resultData.put("modelName", modelResponse.getModelName());
        resultData.put("promptVersion", modelResponse.getPromptVersion());
        resultData.put("costMillis", modelResponse.getCostMillis());
        resultData.put("fallback", modelResponse.getFallback());
        resultData.put("confidence", modelResponse.getConfidence());
        resultData.put("reasoning", modelResponse.getReasoning());

        AgentActionLog actionLog = new AgentActionLog()
                .setId(nextAgentId())
                .setSessionId(sessionId)
                .setShopId(shopId)
                .setOperatorId(merchantId)
                .setOperatorType("agent")
                .setActionType("model_call")
                .setTargetType("model")
                .setTargetId(sessionId)
                .setRequestData(toSimpleJson(requestData))
                .setResultData(toSimpleJson(resultData))
                .setStatus(Boolean.TRUE.equals(modelResponse.getFallback()) ? 2 : 1)
                .setErrorMsg(Boolean.TRUE.equals(modelResponse.getFallback()) ? modelResponse.getReasoning() : null);
        agentActionLogService.save(actionLog);
    }

    /**
     * 记录 Tool Calling 模型调用概况。
     *
     * <p>业务意义：这一条日志回答“这一轮 Agent 对话用了哪个模型、哪个 Prompt 版本、
     * 是否召回 RAG、最终耗时多少”。它是对整轮模型编排的审计，不替代单个工具执行日志。</p>
     */
    private void recordToolCallingModelAction(Long sessionId, Long shopId, Long merchantId,
                                              MerchantAgentChatRequest request,
                                              Map<String, Object> toolCallingResult) {
        Map<String, Object> promptContext = asMap(toolCallingResult.get("promptContext"));
        Map<String, Object> requestData = new LinkedHashMap<>();
        requestData.put("message", request == null ? null : request.getMessage());
        requestData.put("dateRange", request == null ? null : request.getDateRange());
        requestData.put("intent", promptContext.get("intent"));
        requestData.put("promptVersion", toolCallingResult.get("promptVersion"));
        requestData.put("ragRetrievalMode", promptContext.get("ragRetrievalMode"));
        requestData.put("ragHitCount", asList(promptContext.get("ragKnowledge")).size());
        requestData.put("calledTools", toolCallingResult.get("calledTools"));

        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("provider", toolCallingResult.get("provider"));
        resultData.put("modelName", toolCallingResult.get("modelName"));
        resultData.put("promptVersion", toolCallingResult.get("promptVersion"));
        resultData.put("toolCalling", toolCallingResult.get("toolCalling"));
        resultData.put("costMillis", toolCallingResult.get("costMillis"));
        resultData.put("reply", shortText(String.valueOf(toolCallingResult.get("reply")), 300));

        AgentActionLog actionLog = new AgentActionLog()
                .setId(nextAgentId())
                .setSessionId(sessionId)
                .setShopId(shopId)
                .setOperatorId(merchantId)
                .setOperatorType("agent")
                .setActionType("tool_calling_model_call")
                .setTargetType("model")
                .setTargetId(sessionId)
                .setRequestData(toSimpleJson(requestData))
                .setResultData(toSimpleJson(resultData))
                .setStatus(1);
        agentActionLogService.save(actionLog);
    }

    /**
     * 逐条记录模型选择并执行的只读工具。
     *
     * <p>业务意义：Tool Calling 的关键不是“模型回复了一句话”，而是模型基于问题选择了什么工具、
     * 给工具传了什么参数、工具是否成功、耗时多少、返回了什么业务结果。逐条落库后，后续排查
     * Agent 回复质量时，可以明确判断问题出在模型选错工具、工具参数错误，还是业务数据本身不足。</p>
     */
    private void recordToolCallingToolActions(Long sessionId, Long shopId, Long merchantId,
                                              Map<String, Object> toolCallingResult) {
        List<Object> toolCalls = asList(toolCallingResult.get("toolCalls"));
        for (Object item : toolCalls) {
            Map<String, Object> toolCall = asMap(item);
            if (toolCall.isEmpty()) {
                continue;
            }
            String toolName = String.valueOf(toolCall.get("toolName"));
            boolean success = Boolean.TRUE.equals(toolCall.get("success"));

            Map<String, Object> requestData = new LinkedHashMap<>();
            requestData.put("toolName", toolName);
            requestData.put("arguments", toolCall.get("arguments"));

            Map<String, Object> resultData = new LinkedHashMap<>();
            resultData.put("toolName", toolName);
            resultData.put("success", success);
            resultData.put("costMillis", toolCall.get("costMillis"));
            resultData.put("result", toolCall.get("result"));

            String errorMsg = null;
            if (!success) {
                Map<String, Object> result = asMap(toolCall.get("result"));
                errorMsg = shortText(String.valueOf(result.getOrDefault("error", "工具执行失败")), 500);
            }

            AgentActionLog actionLog = new AgentActionLog()
                    .setId(nextAgentId())
                    .setSessionId(sessionId)
                    .setShopId(shopId)
                    .setOperatorId(merchantId)
                    .setOperatorType("agent")
                    .setActionType("tool_call_execute")
                    .setTargetType("tool")
                    .setTargetId(sessionId)
                    .setRequestData(toSimpleJson(requestData))
                    .setResultData(toSimpleJson(resultData))
                    .setStatus(success ? 1 : 2)
                    .setErrorMsg(errorMsg);
            agentActionLogService.save(actionLog);
        }
    }

    private List<Map<String, Object>> buildActionRows(List<AgentActionLog> actionLogs) {
        if (actionLogs == null || actionLogs.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentActionLog actionLog : actionLogs) {
            Map<String, Object> row = new LinkedHashMap<>();
            // 审计日志中同样存在雪花 ID，给前端补充字符串字段，避免 JS 精度丢失。
            row.put("id", actionLog.getId());
            row.put("actionId", String.valueOf(actionLog.getId()));
            row.put("sessionId", actionLog.getSessionId() == null ? null : String.valueOf(actionLog.getSessionId()));
            row.put("shopId", actionLog.getShopId());
            row.put("operatorId", actionLog.getOperatorId() == null ? null : String.valueOf(actionLog.getOperatorId()));
            row.put("operatorType", actionLog.getOperatorType());
            row.put("operatorTypeName", resolveOperatorTypeName(actionLog.getOperatorType()));
            row.put("actionType", actionLog.getActionType());
            row.put("actionTypeName", resolveActionTypeName(actionLog.getActionType()));
            row.put("targetType", actionLog.getTargetType());
            row.put("targetTypeName", resolveTargetTypeName(actionLog.getTargetType()));
            row.put("targetId", actionLog.getTargetId());
            row.put("targetIdText", actionLog.getTargetId() == null ? null : String.valueOf(actionLog.getTargetId()));
            row.put("requestData", actionLog.getRequestData());
            row.put("resultData", actionLog.getResultData());
            row.put("status", actionLog.getStatus());
            row.put("statusName", resolveActionStatusName(actionLog.getStatus()));
            row.put("errorMsg", actionLog.getErrorMsg());
            row.put("createTime", actionLog.getCreateTime());
            result.add(row);
        }
        return result;
    }

    private Long resolveConfirmedVoucherId(AgentCampaignDraft draft) {
        List<AgentActionLog> actionLogs = agentActionLogService.query()
                .eq("shop_id", draft.getShopId())
                .eq("action_type", "confirm_campaign_draft")
                .eq("status", 1)
                .orderByDesc("create_time")
                .list();
        for (AgentActionLog actionLog : actionLogs) {
            Map<String, Object> resultData = parseJsonMap(actionLog.getResultData());
            Object draftIdValue = resultData.get("draftId");
            if (draftIdValue != null && String.valueOf(draft.getId()).equals(String.valueOf(draftIdValue))) {
                Long voucherId = toLong(resultData.get("voucherId"));
                if (voucherId != null) {
                    return voucherId;
                }
                return actionLog.getTargetId();
            }
        }
        return null;
    }

    private Voucher resolveShopVoucher(Long shopId, Long voucherId) {
        List<Voucher> vouchers = voucherAgentTool.queryShopVouchers(shopId);
        for (Voucher voucher : vouchers) {
            if (voucher.getId() != null && voucher.getId().equals(voucherId)) {
                return voucher;
            }
        }
        return null;
    }

    private CampaignEffectContext buildCampaignEffectContext(AgentCampaignDraft draft) {
        Long voucherId = resolveConfirmedVoucherId(draft);
        Voucher voucher = voucherId == null ? null : resolveShopVoucher(draft.getShopId(), voucherId);
        if (voucher == null) {
            return new CampaignEffectContext(voucherId, null, Collections.emptyList(), new OrderStatsDTO());
        }
        LocalDateTime startTime = draft.getBeginTime() == null ? draft.getCreateTime() : draft.getBeginTime();
        List<VoucherOrder> orders = orderAgentTool.queryOrders(Collections.singletonList(voucherId), startTime);
        Map<Long, Voucher> voucherMap = Collections.singletonMap(voucherId, voucher);
        OrderStatsDTO orderStats = orderAgentTool.buildOrderAnalysis(orders, voucherMap);
        return new CampaignEffectContext(voucherId, voucher, orders, orderStats);
    }

    private AgentRecommendationDTO buildEffectRecommendation(AgentCampaignDraft draft, CampaignEffectContext context) {
        OrderStatsDTO orderStats = context.getOrderStats();
        String status = resolveCampaignStatus(draft);
        if ("未开始".equals(status)) {
            return buildRecommendation("seckill", "提前检查活动投放准备",
                    "活动已创建但尚未开始，当前阶段重点不是加码投放，而是保证上线前的展示质量。",
                    "建议检查标题、库存、开始时间和店铺详情页展示位置；如标题不够清晰，可生成一版更强调优惠力度的新草稿。",
                    2, 1, "减少活动开始后的冷启动损耗，提升用户第一眼理解成本。");
        }
        if (orderStats.getTotalOrders() == 0) {
            return buildRecommendation("seckill", "提高活动吸引力并重新投放",
                    "活动已进入投放周期但暂未产生订单，说明曝光或优惠表达可能不足。",
                    "建议生成一张标题更直接、售价略低、库存较小的秒杀券，用低风险方式重新测试转化。",
                    1, 2, "用小库存控制成本，同时验证更强优惠表达是否能带来下单。");
        }
        if (orderStats.getPaidOrders() == 0) {
            return buildRecommendation("voucher", "降低支付门槛促进成交",
                    "已有用户下单但没有支付，说明用户对优惠感兴趣，但支付临门一脚不足。",
                    "建议生成一张门槛更低的代金券，或将秒杀券售价下调后重新测试支付转化。",
                    1, 2, "减少用户支付犹豫，优先把下单意愿转化为真实收入。");
        }
        if (orderStats.getUsedOrders() == 0) {
            return buildRecommendation("voucher", "提升到店核销转化",
                    "活动已有支付但暂未核销，说明用户购买后还没有完成到店消费。",
                    "建议生成一张复购提醒型代金券，并配合到店核销提示，推动已购买用户到店。",
                    2, 1, "提高支付后的到店率，让优惠券从收入进一步转化为真实消费体验。");
        }
        return buildRecommendation("seckill", "复制高效活动开启下一轮",
                "活动已经产生支付和核销，说明当前优惠策略具备继续放大的基础。",
                "建议复制本次活动结构，生成下一轮小库存秒杀券，并在库存不足前提前投放。",
                1, 1, "延续有效策略，减少重新试错成本，提升连续活动运营效率。");
    }

    private Long saveEffectSuggestion(AgentCampaignDraft draft, CampaignEffectContext context,
                                      AgentRecommendationDTO recommendation) {
        AgentSuggestion suggestion = new AgentSuggestion()
                .setId(nextAgentId())
                .setSessionId(resolveDraftSessionId(draft))
                .setShopId(draft.getShopId())
                .setSuggestionType(recommendation.getType())
                .setTitle(recommendation.getTitle())
                .setSummary(recommendation.getReason())
                .setContent(recommendation.getAction() + " 预期效果：" + recommendation.getExpectedEffect())
                .setConfidenceScore(resolveEffectConfidence(context.getOrderStats()))
                .setRiskLevel(recommendation.getRiskLevel() == null ? 1 : recommendation.getRiskLevel())
                .setStatus(1);
        agentSuggestionService.save(suggestion);
        return suggestion.getId();
    }

    private AgentCampaignDraft createDraftFromEffectSuggestion(Long suggestionId,
                                                               AgentRecommendationDTO recommendation,
                                                               CampaignEffectContext context) {
        AgentSuggestion suggestion = agentSuggestionService.getById(suggestionId);
        Shop shop = shopAgentTool.getShop(suggestion.getShopId());
        MerchantCampaignDraftRequest request = new MerchantCampaignDraftRequest();
        request.setRecommendationType(recommendation.getType());
        request.setRecommendationTitle(recommendation.getTitle());
        request.setRecommendationReason(recommendation.getReason());
        request.setRecommendationAction(recommendation.getAction());
        applyEffectDraftDefaults(request, recommendation, context);

        AgentCampaignDraft draft = voucherAgentTool.buildCampaignDraft(suggestion, shop, request, nextAgentId());
        campaignDraftService.save(draft);
        agentSuggestionService.updateById(new AgentSuggestion()
                .setId(suggestionId)
                .setStatus(2));
        recordAction(suggestion.getSessionId(), shop.getId(), currentMerchantId(),
                "create_effect_draft", "draft", draft.getId(), voucherAgentTool.draftToMap(draft));
        return draft;
    }

    private void applyEffectDraftDefaults(MerchantCampaignDraftRequest request,
                                          AgentRecommendationDTO recommendation,
                                          CampaignEffectContext context) {
        Voucher voucher = context.getVoucher();
        long actualValue = voucher == null || voucher.getActualValue() == null ? 15000L : voucher.getActualValue();
        long payValue = voucher == null || voucher.getPayValue() == null ? 8850L : voucher.getPayValue();
        if ("voucher".equals(recommendation.getType())) {
            request.setDraftType("voucher");
            request.setPayValue(Math.max(100L, payValue - 1000L));
            request.setActualValue(actualValue);
        } else {
            request.setDraftType("seckill");
            request.setPayValue(Math.max(100L, payValue - 500L));
            request.setActualValue(actualValue);
            request.setStock(50);
        }
        request.setBeginTime(LocalDateTime.now().plusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0));
        request.setEndTime(request.getBeginTime().plusDays(2));
        request.setRules("{\"limitPerUser\":1,\"verify\":\"到店出示券码核销\",\"source\":\"agent-effect\"}");
    }

    private Long resolveDraftSessionId(AgentCampaignDraft draft) {
        if (draft.getSuggestionId() == null) {
            return null;
        }
        AgentSuggestion suggestion = agentSuggestionService.getById(draft.getSuggestionId());
        return suggestion == null ? null : suggestion.getSessionId();
    }

    private BigDecimal resolveEffectConfidence(OrderStatsDTO orderStats) {
        if (orderStats.getTotalOrders() == 0) {
            return new BigDecimal("68.00");
        }
        if (orderStats.getPaidOrders() == 0 || orderStats.getUsedOrders() == 0) {
            return new BigDecimal("76.00");
        }
        return new BigDecimal("84.00");
    }

    private Map<String, Object> voucherToEffectMap(Voucher voucher) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", voucher.getId());
        row.put("voucherId", String.valueOf(voucher.getId()));
        row.put("shopId", voucher.getShopId());
        row.put("title", voucher.getTitle());
        row.put("subTitle", voucher.getSubTitle());
        row.put("type", voucher.getType());
        row.put("typeName", voucher.getType() != null && voucher.getType() == 1 ? "秒杀券" : "普通代金券");
        row.put("payValue", voucher.getPayValue());
        row.put("actualValue", voucher.getActualValue());
        row.put("status", voucher.getStatus());
        row.put("beginTime", voucher.getBeginTime());
        row.put("endTime", voucher.getEndTime());
        return row;
    }

    private String resolveEffectLevel(OrderStatsDTO orderStats) {
        if (orderStats.getPaidOrders() >= 10 && orderStats.getUsedOrders() >= 5) {
            return "表现较好";
        }
        if (orderStats.getPaidOrders() > 0) {
            return "已有成交";
        }
        return "待观察";
    }

    private String resolveCampaignStatus(AgentCampaignDraft draft) {
        LocalDateTime now = LocalDateTime.now();
        if (draft.getBeginTime() != null && draft.getBeginTime().isAfter(now)) {
            return "未开始";
        }
        if (draft.getEndTime() != null && draft.getEndTime().isBefore(now)) {
            return "已结束";
        }
        return "进行中";
    }

    private String buildCampaignEffectInsight(AgentCampaignDraft draft, Voucher voucher, OrderStatsDTO orderStats) {
        if (draft.getBeginTime() != null && draft.getBeginTime().isAfter(LocalDateTime.now())) {
            return "活动《" + voucher.getTitle() + "》已创建，将在" + draft.getBeginTime()
                    + "开始。当前还没有进入投放周期，建议先检查活动标题、库存和展示位置。";
        }
        if (orderStats.getTotalOrders() == 0) {
            return "活动《" + voucher.getTitle() + "》已创建，但暂未产生订单，建议检查店铺详情页曝光、活动标题和优惠力度。";
        }
        return "活动《" + voucher.getTitle() + "》已产生" + orderStats.getTotalOrders()
                + "笔订单，其中已支付" + orderStats.getPaidOrders()
                + "笔，已核销" + orderStats.getUsedOrders()
                + "笔，预计收入¥" + formatFen(orderStats.getEstimatedRevenue())
                + "。草稿策略是：" + (isBlank(draft.getReason()) ? "暂无策略说明" : draft.getReason());
    }

    private String buildCampaignNextAction(OrderStatsDTO orderStats) {
        if (orderStats.getTotalOrders() == 0) {
            return "建议先提高活动曝光，或让 Agent 重新生成更有吸引力的标题和优惠力度。";
        }
        if (orderStats.getPaidOrders() == 0) {
            return "已有用户下单但未支付，建议检查支付转化链路，并考虑降低购买门槛。";
        }
        if (orderStats.getUsedOrders() == 0) {
            return "已有支付但暂未核销，建议提醒用户到店使用，并关注核销转化。";
        }
        return "活动已有支付和核销，可以继续观察复购，并在库存不足前生成下一轮活动草稿。";
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private DateRange resolveDateRange(String dateRange) {
        String code = dateRange == null || dateRange.trim().isEmpty() ? "LAST_30_DAYS" : dateRange.trim().toUpperCase();
        LocalDateTime end = LocalDateTime.now();
        switch (code) {
            case "TODAY":
                return new DateRange("TODAY", "今天", LocalDate.now().atStartOfDay(), end);
            case "LAST_7_DAYS":
                return new DateRange("LAST_7_DAYS", "近7天", end.minusDays(7), end);
            case "LAST_30_DAYS":
                return new DateRange("LAST_30_DAYS", "近30天", end.minusDays(30), end);
            default:
                return new DateRange("LAST_30_DAYS", "近30天", end.minusDays(30), end);
        }
    }

    private Long currentMerchantId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? 0L : user.getId();
    }

    private String resolveSceneName(String scene) {
        if ("operation_report".equals(scene)) {
            return "运营报告";
        }
        if ("voucher_plan".equals(scene)) {
            return "优惠券方案";
        }
        if ("review_reply".equals(scene)) {
            return "评价回复";
        }
        if ("order_analysis".equals(scene)) {
            return "订单分析";
        }
        if ("operation_chat".equals(scene)) {
            return "运营对话";
        }
        return "未知场景";
    }

    private String resolveSessionStatusName(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        switch (status) {
            case 1:
                return "进行中";
            case 2:
                return "已完成";
            case 3:
                return "已关闭";
            default:
                return "未知状态";
        }
    }

    private String resolveSuggestionTypeName(String type) {
        if ("voucher".equals(type)) {
            return "普通券建议";
        }
        if ("seckill".equals(type)) {
            return "秒杀券建议";
        }
        if ("review".equals(type)) {
            return "评价建议";
        }
        if ("operation".equals(type)) {
            return "运营建议";
        }
        return "未知建议";
    }

    private String resolveRiskLevelName(Integer riskLevel) {
        if (riskLevel == null) {
            return "未知风险";
        }
        switch (riskLevel) {
            case 1:
                return "低风险";
            case 2:
                return "中风险";
            case 3:
                return "高风险";
            default:
                return "未知风险";
        }
    }

    private String resolveSuggestionStatusName(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        switch (status) {
            case 1:
                return "待确认";
            case 2:
                return "已采纳";
            case 3:
                return "已拒绝";
            case 4:
                return "已执行";
            default:
                return "未知状态";
        }
    }

    private String resolveOperatorTypeName(String operatorType) {
        if ("merchant".equals(operatorType)) {
            return "商家";
        }
        if ("agent".equals(operatorType)) {
            return "Agent";
        }
        if ("system".equals(operatorType)) {
            return "系统";
        }
        return "未知操作方";
    }

    private String resolveActionTypeName(String actionType) {
        if ("generate_operation_report".equals(actionType)) {
            return "生成运营报告";
        }
        if ("create_campaign_draft".equals(actionType)) {
            return "生成活动草稿";
        }
        if ("rename_agent_session".equals(actionType)) {
            return "重命名历史会话";
        }
        if ("delete_agent_session".equals(actionType)) {
            return "删除历史会话";
        }
        if ("delete_agent_suggestion".equals(actionType)) {
            return "删除智能行动";
        }
        if ("delete_campaign_draft".equals(actionType)) {
            return "删除活动草稿";
        }
        if ("clear_campaign_drafts".equals(actionType)) {
            return "清空活动草稿";
        }
        if ("update_campaign_draft".equals(actionType)) {
            return "修改活动草稿";
        }
        if ("reject_campaign_draft".equals(actionType)) {
            return "拒绝活动草稿";
        }
        if ("confirm_campaign_draft".equals(actionType)) {
            return "确认创建活动";
        }
        if ("query_campaign_effect".equals(actionType)) {
            return "查看活动复盘";
        }
        if ("create_effect_suggestion".equals(actionType)) {
            return "生成复盘建议";
        }
        if ("create_effect_draft".equals(actionType)) {
            return "生成复盘草稿";
        }
        if ("model_call".equals(actionType)) {
            return "模型调用";
        }
        if ("tool_calling_chat".equals(actionType)) {
            return "Tool Calling对话";
        }
        if ("tool_calling_model_call".equals(actionType)) {
            return "Tool Calling模型调用";
        }
        if ("tool_call_execute".equals(actionType)) {
            return "执行Agent工具";
        }
        if ("agent_chat".equals(actionType)) {
            return "Agent对话";
        }
        if ("create_chat_suggestion".equals(actionType)) {
            return "生成对话建议";
        }
        return "未知操作";
    }

    private String resolveTargetTypeName(String targetType) {
        if ("suggestion".equals(targetType)) {
            return "Agent建议";
        }
        if ("draft".equals(targetType)) {
            return "活动草稿";
        }
        if ("voucher".equals(targetType)) {
            return "优惠券";
        }
        if ("seckill".equals(targetType)) {
            return "秒杀券";
        }
        if ("model".equals(targetType)) {
            return "大模型";
        }
        if ("tool".equals(targetType)) {
            return "Agent工具";
        }
        if ("session".equals(targetType)) {
            return "Agent会话";
        }
        return "未知目标";
    }

    private String resolveActionStatusName(Integer status) {
        if (status == null) {
            return "未知状态";
        }
        switch (status) {
            case 1:
                return "成功";
            case 2:
                return "失败";
            default:
                return "未知状态";
        }
    }

    private String toSimpleJson(Map<String, Object> data) {
        if (data == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return data.toString();
        }
    }

    private String toSimpleJson(Object data) {
        if (data == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            return data.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return Collections.emptyList();
    }

    private String shortText(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private long nextAgentId() {
        // Agent 模块涉及会话、消息、建议、草稿、审计多张表。统一使用同一个 keyPrefix，
        // 可以避免同一秒内不同业务 key 生成相同数值，前端调试和日志追踪会更直观。
        return redisIdWorker.nextId("agent");
    }

    private static class AgentContext {
        private Shop shop;
        private ShopProfileDTO shopProfile;
        private OrderStatsDTO orderAnalysis;
        private VoucherStatsDTO voucherAnalysis;
        private ReviewStatsDTO reviewAnalysis;
        private List<AgentRecommendationDTO> recommendations;

        private Shop getShop() {
            return shop;
        }

        private void setShop(Shop shop) {
            this.shop = shop;
        }

        private ShopProfileDTO getShopProfile() {
            return shopProfile;
        }

        private void setShopProfile(ShopProfileDTO shopProfile) {
            this.shopProfile = shopProfile;
        }

        private OrderStatsDTO getOrderAnalysis() {
            return orderAnalysis;
        }

        private void setOrderAnalysis(OrderStatsDTO orderAnalysis) {
            this.orderAnalysis = orderAnalysis;
        }

        private VoucherStatsDTO getVoucherAnalysis() {
            return voucherAnalysis;
        }

        private void setVoucherAnalysis(VoucherStatsDTO voucherAnalysis) {
            this.voucherAnalysis = voucherAnalysis;
        }

        private ReviewStatsDTO getReviewAnalysis() {
            return reviewAnalysis;
        }

        private void setReviewAnalysis(ReviewStatsDTO reviewAnalysis) {
            this.reviewAnalysis = reviewAnalysis;
        }

        private List<AgentRecommendationDTO> getRecommendations() {
            return recommendations;
        }

        private void setRecommendations(List<AgentRecommendationDTO> recommendations) {
            this.recommendations = recommendations;
        }
    }

    private static class CampaignEffectContext {
        private final Long voucherId;
        private final Voucher voucher;
        private final List<VoucherOrder> orders;
        private final OrderStatsDTO orderStats;

        private CampaignEffectContext(Long voucherId, Voucher voucher,
                                      List<VoucherOrder> orders, OrderStatsDTO orderStats) {
            this.voucherId = voucherId;
            this.voucher = voucher;
            this.orders = orders;
            this.orderStats = orderStats;
        }

        private Long getVoucherId() {
            return voucherId;
        }

        private Voucher getVoucher() {
            return voucher;
        }

        private List<VoucherOrder> getOrders() {
            return orders;
        }

        private OrderStatsDTO getOrderStats() {
            return orderStats;
        }
    }

    private static class DateRange {
        private final String code;
        private final String label;
        private final LocalDateTime startTime;
        private final LocalDateTime endTime;

        private DateRange(String code, String label, LocalDateTime startTime, LocalDateTime endTime) {
            this.code = code;
            this.label = label;
            this.startTime = startTime;
            this.endTime = endTime;
        }

        private String getCode() {
            return code;
        }

        private String getLabel() {
            return label;
        }

        private LocalDateTime getStartTime() {
            return startTime;
        }

        private LocalDateTime getEndTime() {
            return endTime;
        }
    }
}
