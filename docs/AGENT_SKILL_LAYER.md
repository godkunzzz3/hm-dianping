# Agent Skill Layer

## 1. 背景

本项目在 Agent Tool 之上增加了一层轻量级 Agent Skill 编排层，用于把原子业务能力组合成面向商家运营目标的可控执行单元。

- Tool 是原子业务能力，例如店铺画像、订单分析、优惠券分析、评价摘要。
- Skill 是面向业务目标的编排，例如商家运营诊断、商家知识库问答、优惠券草稿生成。
- Workflow 是执行观测、回放和评测基础设施，用于记录 Skill 开始、结束、失败等步骤。
- HITL 用于控制高风险写操作。模型和 Skill 不能直接创建真实优惠券，高风险动作必须先生成草稿，再由商家确认。

这一层的目标不是替代 Tool Calling，而是把 Tool / RAG / Rule Policy / Draft Service 组合成更清晰、可测试、可观测的 Agent 能力边界。

## 2. 架构分层

执行链路可以概括为：

```text
User Request
-> Agent Skill
-> Tool / RAG / Rule Policy / Draft Service
-> Workflow Step
-> SkillResult
```

三层关系：

- Tool = 原子能力。负责单个领域的数据查询、分析或草稿构造。
- Skill = 业务目标编排。负责把多个 Tool、RAG 检索、规则判断和草稿服务组合起来。
- Workflow = 执行过程观测。负责旁路记录执行过程，便于回放、评测和失败定位。

当前 Skill 层没有接入主聊天流程，也没有新增 Controller。它作为后端可测试的编排层独立存在，后续可以按需接入 Agent 工作台或执行回放页面。

## 3. Skill Registry 与执行模型

核心类：

- `AgentSkill<I, O>`：统一 Skill 接口，声明 `name`、`definition`、`inputType` 和 `execute`。
- `SkillDefinition`：Skill 元数据，包含 `skillName`、`allowedTools`、`riskLevel`、`needHumanConfirm`、`modelCallable` 等字段。
- `SkillContext`：执行上下文，包含 `shopId`、`userId`、`sessionId`、`workflowRunId`、`traceId` 和扩展属性。
- `SkillResult<O>`：统一执行结果，保证 `usedTools` 和 `metadata` 默认非空。
- `SkillRegistry`：启动时注入所有 `AgentSkill` Bean，按 `skillName` 建立 Map，重名 Skill 会直接报错。
- `SkillExecutionService`：负责按名称查找 Skill、转换输入类型、统一包装异常，并在有 `workflowRunId` 时旁路记录 `SKILL_START` / `SKILL_FINAL` / `SKILL_FAILED`。

设计约束：

- `modelCallable` 默认是 `false`。
- 第一版 Skill 不直接暴露给模型自由调用。
- SkillExecutionService 会捕获异常并返回 `SkillResult.failure`，避免异常裸抛到上层。
- Workflow 记录是旁路能力，记录失败不应该影响 Skill 主执行。

## 4. 已实现 Skill

### 4.1 MerchantDiagnosisSkill

目标：商家运营诊断。

编排工具：

- `shop_profile_tool`
- `order_analysis_tool`
- `voucher_analysis_tool`
- `review_content_tool`

安全边界：

- 只读 Skill。
- 不调用 `operation_diagnosis_tool`，避免 Skill 只是包一层综合诊断工具。
- 不调用 `voucher_campaign_tool`。
- 不执行写库操作。
- 不生成草稿。
- 不触发 Human-in-the-loop。
- `riskLevel=LOW`。
- `needHumanConfirm=false`。

说明：`MerchantDiagnosisSkill` 的价值在于编排多个原子只读工具，再输出结构化诊断结果，而不是复用已有综合诊断工具。

### 4.2 KnowledgeQaSkill

目标：商家知识库问答。

实现方式：

- 调用 `retrieveForAgentForShop(shopId, intent, question, topK)`。
- 不调用旧的全局 `retrieveForAgent(...)` 作为正式检索逻辑。
- 不调用大模型生成答案。
- 不重新实现 Embedding、向量检索或 rerank。
- 复用现有 RAG Service 的召回、重排、阈值和 `noReliableHit` 能力。

`shop_id` 隔离规则：

- `shop_id = 当前 shopId`：当前商家的私有知识。
- `shop_id IS NULL`：公共知识。
- 商家级检索禁止返回其他 `shopId` 的私有知识。

低置信兜底：

- 当召回为空或 `noReliableHit=true` 时，返回低置信兜底表达。
- 不基于空召回生成肯定性答案。
- 不把其他商家的私有知识写入 `retrievedChunks`、metadata 或日志。

### 4.3 CouponDraftSkill

目标：优惠券策略草稿生成。

实现方式：

- 调用 `order_analysis_tool` 和 `voucher_analysis_tool` 做只读分析。
- 调用 `MerchantAgentRulePolicyService` 做高危操作和确认策略判断。
- 调用 `MerchantCampaignDraftSkillService.createDraftFromSkill(...)` 生成待确认草稿。
- 不直接调用草稿表 Service 保存草稿。

明确禁止：

- 不调用 `confirmCampaignDraft`。
- 不调用 `VoucherAgentTool.createVoucherFromDraft`。
- 不直接调用 `campaignDraftService.save`。
- 不创建真实 `tb_voucher`。
- 不创建真实 `tb_seckill_voucher`。
- 不写 Redis 秒杀库存。
- 不把 `voucher_campaign_tool` 加入 `allowedTools`。

Skill 元数据：

- `allowedTools=[order_analysis_tool, voucher_analysis_tool]`
- `riskLevel=HIGH`
- `needHumanConfirm=true`
- `modelCallable=false`

高危输入处理：

- 命中“直接创建”“绕过确认”“自动确认”“退款”“批量改库存”“超大规模秒杀”等语义时，返回 `PROHIBITED_OPERATION` 或风险提示。
- 不生成真实优惠券。
- 不绕过人工确认。
- 即使命中风险规则，`needHumanConfirm` 仍保持为 `true`。

## 5. 安全设计

### 只读工具白名单

`AgentToolRegistry.listModelCallableDefinitions()` 只返回 `modelCallable=true` 的工具。只读执行入口 `AgentToolExecutor.executeReadonlyTool` 会根据工具元数据拒绝非只读工具。

### 写工具不暴露给模型

`voucher_campaign_tool` 的元数据是：

- `toolType=draft`
- `accessLevel=write`
- `writeDatabase=true`
- `requireMerchantConfirm=true`
- `modelCallable=false`
- `executionPolicy=draft_only`

因此它不会出现在模型可调用工具白名单里，也不会进入 `CouponDraftSkill.allowedTools`。

### RAG 商家隔离

`tb_agent_knowledge_doc.shop_id` 用于区分公共知识和商家私有知识：

- `shop_id IS NULL` 表示公共知识。
- `shop_id = 当前 shopId` 表示当前商家的私有知识。

商家级 RAG 检索通过 `retrieveForAgentForShop` 执行，最终结果会过滤掉其他商家的私有知识。安全优先于召回率：宁可 `noReliableHit`，也不能跨商家泄漏知识片段。

### 草稿 + 人工确认

高风险写操作不直接创建真实券，而是先写入活动草稿。真实券创建只能由确认流程触发：

- 草稿生成：只写待确认草稿。
- 商家确认：才允许把草稿转换为真实优惠券。
- 确认前复用草稿字段校验。
- 非待确认草稿不能重复确认。

### 高危操作规则

`MerchantAgentRulePolicyService` 覆盖退款、取消订单、修改支付状态、修改核销状态、修改库存、修改价格、直接创建、大规模秒杀、手机号/隐私信息等风险语义。

### Workflow 记录

`SkillExecutionService` 在存在 `workflowRunId` 时旁路记录 Skill 开始、结束和失败步骤。Workflow 用于观测、回放和评测，不是业务事务的强依赖。

### 异常包装

Skill 执行异常由 `SkillExecutionService` 或 Skill 自身包装成 `SkillResult.failure`。失败结果包含 `errorCode` 和 `errorMessage`，避免异常直接裸抛。

## 6. 测试结果

本轮最终验证命令：

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn -q -Dtest='*Skill*Test,*Knowledge*Test,*Draft*Test,*Campaign*Test,AgentToolRegistrySecurityTest,AgentToolExecutorReadonlySkillSecurityTest' test
```

结果：通过。

全量测试命令：

```bash
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn test
```

全量测试结果：

- Tests run: 128
- Failures: 0
- Errors: 0
- Skipped: 0
- BUILD SUCCESS

相关提交：

- `0bceec4 feat: add lightweight agent skill framework`
- `42bf74f feat: add merchant diagnosis skill`
- `1320514 feat: add shop-scoped knowledge retrieval`
- `19ca70a feat: add shop-scoped knowledge qa skill`
- `ca2a8d2 refactor: add safe campaign draft creation support`
- `704dd14 feat: add coupon draft skill`

## 7. 技术说明

本项目中没有让模型直接自由执行写操作，而是在 Agent Tool 之上抽象 Skill 层。只读 Skill 负责商家诊断和知识库问答，高风险写操作通过 CouponDraftSkill 生成草稿，并强制进入人工确认流程。所有 Skill 默认不直接暴露给模型调用，执行过程由 Workflow 记录，便于回放、评测和失败定位。

可以概括为：

- Tool 是原子能力，适合做最小权限控制。
- Skill 是业务目标编排，适合承载 RAG、规则、只读工具和草稿服务组合。
- Workflow 是观测层，用来解释 Agent 每一步做了什么。
- 写操作永远不交给模型直接执行，而是通过草稿和商家确认实现 Human-in-the-loop。

## 8. 后续可扩展方向

以下是后续方向，不代表当前已经实现：

- Skill Eval 用例集：针对 Skill 的输入、输出、安全边界和失败路径做批量评测。
- RAGAS 生成质量评测：补充答案忠实性、上下文相关性等质量指标。
- MCP Server 封装只读工具：把只读工具协议化给外部 Agent 调用。
- Qdrant / metadata filter：把当前应用层 shop_id 过滤升级为向量索引 metadata filter。
- 前端 Skill Trace 展示：在工作台展示 Skill 执行步骤、输入输出摘要和失败原因。
