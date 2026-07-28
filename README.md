# 本地生活商家智能运营 Agent 平台

这是一个面向本地生活商家的智能运营 Agent 平台，底层覆盖优惠券秒杀、缓存、异步下单、探店社交等 Java/Redis 业务链路，上层将商家运营分析、RAG 知识问答、Tool Calling、Workflow、Agent Eval 和 Preference Memory 整合成一个可观测、可评测、有人审的 Agent 工程化系统。

项目目标不是单纯做一个聊天机器人，而是把已有本地生活业务能力封装成 Agent 可调用的工具，让 Agent 基于真实业务数据为商家生成可执行、可追踪、可确认的运营建议。

## 前后端仓库

本项目采用前后端分离架构：

- 后端仓库：[godkunzzz3/hm-dianping](https://github.com/godkunzzz3/hm-dianping)
- 前端仓库：[godkunzzz3/hmdp-web](https://github.com/godkunzzz3/hmdp-web)

后端负责 Spring Boot 业务接口、Redis 实战能力、Agent Tool Calling、RAG 知识库、活动草稿确认和操作审计。前端负责用户端页面、商家 Agent 工作台、RAG 调试评测、草稿确认和操作审计展示。

### 前端请求与 Nginx 代理

前端静态页面统一通过 `/api` 访问后端接口，`js/common.js` 中配置：

```js
let commonURL = "/api";
axios.defaults.baseURL = commonURL;
```

Nginx 将 `/api/*` 请求 rewrite 后代理到本地后端：

```nginx
location /api {
    rewrite /api(/.*) $1 break;
    proxy_pass http://127.0.0.1:8081;
}
```

因此本地复现时，前端访问地址是 `http://localhost:8080`，后端服务地址是 `http://localhost:8081`。

### 商家演示账号机制

本地演示使用手机号验证码登录。验证码可从 Redis 或后端日志中查看，登录成功后前端会保存 token 并访问商家工作台。

演示数据中，`src/main/resources/db/hmdp.sql` 将 `tb_user` 自增 ID 设置为 1010，`src/main/resources/db/merchant_schema.sql` 默认绑定 `user_id=1010` 和 `shop_id=10143`。因此，在全新导入 SQL 后，首次使用新手机号登录生成的用户通常就是演示商家用户，可访问默认商家店铺。

商家 Agent 演示入口：

```text
http://localhost:8080/merchant-agent.html
```

## 项目亮点总览

### 1. Java / Redis 高并发业务主线

- 覆盖本地生活平台中的登录、店铺、优惠券、订单、签到、关注、Feed、GEO 附近商户和达人探店等业务。
- 秒杀请求通过 Lua 原子完成库存校验、一人一单判断、库存预扣和 Redis Stream 入队，后台消费者异步落库。
- 店铺缓存覆盖缓存穿透、逻辑过期、互斥锁重建和更新后删除缓存等典型一致性场景。
- 使用 BitMap、Set、Sorted Set、GEO 等数据结构实现连续签到、关注关系、滚动分页 Feed 和附近商户查询。

### 2. Agent Tool Calling 主线

- 将店铺档案、订单统计、优惠券分析、评价分析和经营诊断等 Java 业务能力封装为 Agent Tool。
- 通过 Tool Registry 维护工具元数据和模型可调用白名单，只向模型暴露只读工具。
- 写操作不由模型直接执行：Agent 只能生成活动草稿，商家确认后才创建真实优惠券或秒杀券。
- 对删除活动、退款、修改库存、支付状态和用户隐私查询等高风险输入进行规则拒绝和安全评测。

### 3. RAG 与知识库评测主线

- 支持商家知识文档维护、文本分片、Embedding、向量存储和 TopK 召回，并保留关键词召回兜底。
- 召回链路包含规则重排、相似度阈值和 `no_reliable_hit` 质量闸门，低质量知识不会强行进入回答。
- 提供召回调试、知识来源展示、评测用例维护、批量 RAG Eval 和历史运行记录。

### 4. Agent 工程化主线

- 使用 Workflow Run / Step 持久化记录 RAG、意图识别、工具选择、工具执行、模型回复和 Memory 加载等节点。
- Agent Eval 复用线上规则策略，离线评测 intent、tool、confirm、risk 四类确定性行为，不调用真实大模型。
- 自动化测试覆盖 Tool 白名单、活动草稿校验、Workflow、Agent Eval、Memory 和候选记忆安全边界。
- GitHub Actions 在 `push` 和 `pull_request` 时自动执行 `mvn -B test`。

### 5. Memory 与 Human-in-the-loop 主线

- 支持店铺级 Preference Memory 人工新增、编辑、启用、禁用和删除，并注入普通对话与 Tool Calling Prompt。
- Prompt 明确规定 Memory 只表示商家偏好或运营约束，真实订单、库存、优惠券等数据以工具查询结果为准。
- 前端工作台提供正式 Memory 管理入口；Workflow 记录 `MEMORY_LOAD`，但不保存完整敏感内容。
- 第一版通过规则生成候选记忆，候选不会直接进入 Prompt，只有商家编辑并确认后才写入正式 Memory。

## Agent Skill Layer

项目在 Tool Calling、Workflow、Agent Eval 和 Human-in-the-loop 机制之上增加了一层轻量级 Agent Skill 编排：

- Tool 是原子业务能力，例如店铺档案、订单统计、优惠券分析、评价摘要和草稿生成。
- Skill 是面向业务目标的编排单元，负责组合 Tool、RAG、规则校验、输出结构和人工确认策略。
- Workflow 负责记录 Skill 执行过程，支撑执行回放、评测和失败定位。
- 高风险写操作必须进入草稿和商家确认流程，不能由模型直接修改业务数据。

| Skill | 目标 | 关键能力 | 安全边界 |
| --- | --- | --- | --- |
| `MerchantDiagnosisSkill` | 商家运营诊断 | 编排商家画像、订单统计、优惠券分析、评价摘要等只读工具 | 只读，`riskLevel=LOW`，不触发写操作 |
| `KnowledgeQaSkill` | 商家知识库问答 | 基于 `shop_id` 隔离 RAG 检索，支持 `noReliableHit` 低置信兜底 | 只召回当前商家私有知识和公共知识，不跨商家返回 `retrievedChunks` |
| `CouponDraftSkill` | 优惠券策略草稿生成 | 生成待确认活动草稿，复用草稿校验和高危规则 | `riskLevel=HIGH`，`needHumanConfirm=true`，不创建真实券 |

安全设计：

- Skill 默认 `modelCallable=false`，不直接暴露给模型自由调用。
- 只读工具通过 Tool Registry 白名单控制。
- `voucher_campaign_tool` 不进入模型可调用列表。
- `CouponDraftSkill` 只调用 `MerchantCampaignDraftSkillService.createDraftFromSkill`。
- 禁止调用 `confirmCampaignDraft` / `createVoucherFromDraft`。
- 知识库 RAG 使用 `shop_id` 隔离，商家级检索只返回 `shop_id=当前 shopId` 或 `shop_id IS NULL` 的公共知识。

测试结果：

- 全量测试通过：`Tests run: 128, Failures: 0, Errors: 0, Skipped: 0`。
- 覆盖 Skill、Knowledge、Draft、Campaign、Tool Registry Security、Readonly Tool Executor Security 等测试。

详细设计见 [docs/AGENT_SKILL_LAYER.md](docs/AGENT_SKILL_LAYER.md)。

## 项目截图

### 用户端店铺详情与优惠券

![用户端店铺详情与优惠券](docs/assets/shop-detail.png)

### 商家运营 Agent 工作台与草稿管理

![商家运营 Agent 工作台](docs/assets/merchant-agent-overview.png)

### RAG 知识库召回与批量评测

![RAG 知识库召回与批量评测](docs/assets/rag-evaluation.png)

## 技术栈

- JDK 17（业务代码源于 Java 8 / Spring Boot 2.3 生态，当前 Agent / DashScope 依赖使用 JDK 17 构建和运行）
- Spring Boot 2.3.12
- MyBatis-Plus
- MySQL
- Redis
- Lua
- Redis Stream
- Hutool
- LangChain4j
- DashScope / 通义千问
- Vue 2 + Element UI
- Nginx 静态前端

## 核心业务模块

### 用户端业务

- 手机号验证码登录
- 用户资料编辑
- 店铺分类与店铺详情
- 附近商户与排序筛选
- 普通代金券购买
- 秒杀券抢购
- 用户订单与券码展示
- 签到与连续签到统计
- 达人探店笔记发布
- 探店点赞与点赞榜
- 关注、取关、共同关注
- 关注 Feed 流

### Redis 实战能力

| 场景 | Redis 能力 |
| --- | --- |
| 店铺缓存 | String 缓存、缓存穿透处理 |
| 秒杀下单 | Lua 原子扣减、一人一单校验 |
| 异步下单 | Redis Stream 消息队列 |
| 签到 | BitMap |
| 关注关系 | Set |
| Feed 流 | Sorted Set |
| 附近商户 | GEO |

### 商家运营 Agent 模块

Agent 模块面向商家，支持：

- 查询店铺经营报告
- 分析订单表现
- 分析优惠券结构
- 分析评价和探店内容
- 生成运营建议
- 生成优惠券/秒杀券活动草稿
- 商家确认后创建真实活动
- 活动效果复盘
- RAG 知识库维护
- 知识文档向量化
- RAG 召回调试
- RAG 批量评测和评测用例持久化

## Agent 设计原则

本项目中的 Agent 遵守一个核心原则：

> Agent 负责分析、建议和准备方案；商家负责确认关键动作。

因此，Agent 不会直接执行高风险操作：

- 不自动退款
- 不自动取消订单
- 不自动删除活动
- 不自动群发消息
- 不绕过商家确认创建真实优惠券或秒杀券
- 不修改支付、核销、库存等高风险状态

## 开发笔记维护约定

后续每完成一个功能点，除了提交代码，也同步维护项目笔记，方便复盘和面试讲解。

固定写法：

1. `README.md`：记录功能背景、业务逻辑、核心流程和验证方式。
2. `docs/INTERVIEW_GUIDE.md`：记录面试讲法、设计取舍和可能追问。
3. `docs/DEMO_SCRIPT.md`：如果功能涉及页面演示，同步补充演示路径和讲解话术。

每个功能点建议按以下结构记录：

```text
功能目标：
解决了什么业务问题。

业务逻辑：
用户/商家触发什么动作，后端如何处理，哪些数据会被读写。

工程设计：
为什么这样分层，哪些边界在后端兜底。

验证方式：
编译、接口、页面或数据库验证结果。

面试讲法：
一句话讲清楚亮点，以及面试官可能追问什么。
```

### 近期开发记录：Agent 活动草稿安全校验

功能目标：

Agent 可以根据运营建议生成优惠券/秒杀券草稿，但大模型和前端输入都不能完全信任，因此需要在创建草稿、编辑草稿和确认创建真实券之前增加后端安全校验。

业务逻辑：

1. Agent 生成运营建议。
2. 后端根据建议生成 `tb_agent_campaign_draft` 草稿。
3. 商家可以编辑标题、副标题、金额、库存、活动时间和规则。
4. 商家确认后，后端才把草稿转换为 `tb_voucher` / `tb_seckill_voucher`。
5. 确认前必须再次校验，避免异常草稿进入真实业务表。

工程设计：

- `subTitle` 只保存一句短卖点，完整分析保存在 `reason`。
- `title`、`subTitle`、`rules` 在后端做长度保护，避免写入数据库时报 `Data truncation`。
- 金额必须大于 0，且支付金额必须小于抵扣金额。
- 秒杀券库存必须大于 0，并限制最大库存。
- 秒杀活动和普通券分别限制最大有效期。
- 确认创建真实券前复用同一套校验逻辑，避免“编辑时通过、确认时异常”。

验证方式：

- 后端编译通过。
- 接口测试副标题超长、库存过大、金额倒挂时，均返回业务错误，不再变成数据库异常。
- 测试生成草稿时，`subTitle` 被压缩为短文案，长运营说明保留在 `reason`。

前端配套：

- 商家编辑草稿时，页面同步限制标题、副标题、金额、库存和规则长度。
- 前端会优先给出友好提示，减少无效请求。
- 后端仍然保留最终校验，防止绕过前端或接口被异常调用。

### 近期开发记录：Agent Tool 标准化第一步

功能目标：

把后端业务能力整理成更标准的 Agent Tool 元数据，让系统能区分“模型可直接调用的只读工具”和“必须由后端流程控制的写工具”。

业务逻辑：

1. 店铺画像、订单分析、评价内容、综合诊断属于只读工具。
2. 优惠券活动工具会生成草稿，后续可能创建真实优惠券，因此不能直接暴露给模型。
3. 前端和调试接口可以查看完整工具清单。
4. Tool Calling 只能使用 `modelCallable=true` 的低风险工具。

工程设计：

- `AgentToolDefinitionDTO` 增加 `toolType`、`modelCallable`、`executionPolicy`、`confirmReason`。
- `AgentToolRegistry` 提供全部工具清单和模型可调用工具清单。
- 新增 `/merchant-agent/tools/model-callable` 接口，用于调试模型可见工具范围。
- 写工具保留在 Human-in-the-loop 流程中，不交给模型自由执行。

验证方式：

- 后端编译通过。
- `/merchant-agent/tools` 能看到全部工具，包括 `voucher_campaign_tool`。
- `/merchant-agent/tools/model-callable` 只返回只读工具，不包含需要商家确认的写工具。

### 近期开发记录：Tool Calling 注册表驱动化

功能目标：

把 LangChain4j Tool Calling 里“模型可见工具”的声明从硬编码改成读取 `AgentToolRegistry`，让后续新增 Tool 时只需要维护工具元数据，减少遗漏和重复配置。

业务逻辑：

1. 每个 Agent Tool 先声明自己的内部工具名、模型函数名、工具类型和执行策略。
2. `AgentToolRegistry` 汇总所有工具定义。
3. Tool Calling 只读取 `modelCallable=true` 的工具暴露给大模型。
4. 模型选择工具后，后端仍然按白名单执行只读工具。
5. 生成草稿、创建真实券等写操作仍不直接暴露给模型，继续走商家确认流程。
6. 优惠券能力拆成“只读结构分析”和“活动草稿生成”两个工具，避免查询能力和写库能力混在一起。

工程设计：

- `AgentToolDefinitionDTO` 增加 `modelToolName`，区分后端内部审计名称和大模型函数名称。
- 例如内部名可以是 `order_analysis_tool`，模型函数名是 `getShopOrderStats`。
- 新增 `voucher_analysis_tool` 作为只读优惠券结构工具，继续保留 `voucher_campaign_tool` 作为草稿写工具。
- `MerchantAgentToolCallingService` 通过 `agentToolRegistry.listModelCallableDefinitions()` 生成 LangChain4j `ToolSpecification`。
- 不同工具按 `modelToolName` 生成不同参数 schema，例如订单统计和综合诊断需要 `dateRange`。
- 新增 `getOperationDiagnosis` 作为综合只读工具，组合店铺、订单、优惠券和评价上下文。

验证方式：

- 后端编译通过。
- `/merchant-agent/tools/model-callable` 返回的工具来自注册表。
- `voucher_campaign_tool` 仍保留在完整工具清单中，但不会进入模型可调用工具列表。
- `getShopVouchers` 作为只读工具进入模型可调用工具列表。

面试讲法：

> 我把 Tool Calling 从手写工具列表升级成注册表驱动。每个工具自己声明是否允许模型调用，LangChain4j 调用层只读取低风险只读工具。这样新增工具时不需要到处改代码，也能避免把写操作误暴露给模型。

### 近期开发记录：Tool Calling 操作审计增强

功能目标：

让 Tool Calling 不只在前端展示执行链路，还要把模型调用概况和每个工具执行明细写入 `tb_agent_action_log`，方便后续排查、审计和面试讲解。

业务逻辑：

1. 商家在 Agent 工作台发起 Tool Calling 对话。
2. 后端创建或复用当前 Agent 会话，并保存用户消息。
3. 模型根据问题选择只读工具。
4. 后端执行工具并返回业务数据。
5. 系统生成最终回复，并保存助手消息。
6. 审计表额外记录一条模型调用概况和多条工具执行明细。

工程设计：

- `tool_calling_model_call`：记录本轮使用的模型、Prompt 版本、RAG 召回模式、召回数量、总耗时和回复摘要。
- `tool_call_execute`：逐条记录模型选择的工具名、入参、执行结果、耗时、成功失败状态。
- 保留原来的 `tool_calling_chat` 总日志，用于查看整轮对话结果。
- 工具执行失败时，单条工具日志状态记为失败，并保存错误原因。

验证方式：

- 后端编译通过。
- Tool Calling 返回结果中的 `toolCalls` 会被拆成多条审计日志。
- `/merchant-agent/shops/{shopId}/actions` 可以继续查询这些日志，并通过 `actionTypeName` 区分模型调用和工具执行。

面试讲法：

> 我没有只把 Agent 的最终回复落库，而是把模型调用和每个工具调用都拆成审计日志。这样当 Agent 回复不理想时，可以判断是模型选错工具、工具参数不对、工具执行失败，还是底层业务数据不足。这是 AI Agent 工程化落地时很关键的可观测性设计。

## Agent 调用流程

```mermaid
flowchart TD
    A["商家输入问题"] --> B["识别业务意图"]
    B --> C["RAG 检索运营知识"]
    C --> D["模型选择只读工具"]
    D --> E["查询店铺/订单/优惠券/评价数据"]
    E --> F["生成商家可读回复"]
    F --> G{"是否需要创建活动?"}
    G -- "否" --> H["返回分析结果"]
    G -- "是" --> I["生成活动草稿"]
    I --> J["商家确认"]
    J --> K["创建真实优惠券或秒杀券"]
    K --> L["记录操作日志"]
```

## 目录结构

```text
src/main/java/com/hmdp
  agent       Agent 模型、Prompt、Tool Calling、Embedding
  controller 业务接口和商家 Agent 接口
  dto        请求响应 DTO、Agent 上下文 DTO
  entity     MySQL 实体
  mapper     MyBatis-Plus Mapper
  service    Service 接口
  service/impl 业务实现和 Agent 编排
  tool       可被 Agent 调用的业务工具
  utils      Redis、ID、用户上下文等工具

src/main/resources
  db         初始化 SQL、Agent 表结构、演示数据
  prompt     Agent Prompt 模板
  seckill.lua 秒杀 Lua 脚本
```

## 数据库脚本

核心 SQL 文件：

- `src/main/resources/db/hmdp.sql`：本地生活点评类基础业务表。
- `src/main/resources/db/merchant_schema.sql`：商家账号相关表。
- `src/main/resources/db/agent_schema.sql`：Agent 会话、消息、建议、草稿、知识库、评测用例等表。
- `src/main/resources/db/seed_shop_demo.sql`：店铺演示数据。
- `src/main/resources/db/seed_voucher_demo.sql`：优惠券演示数据。
- `src/main/resources/db/seed_agent_knowledge.sql`：Agent RAG 知识库种子数据。

## 本地启动

### 1. 准备依赖

需要本地启动：

- JDK 17
- MySQL
- Redis
- Nginx

当前项目的基础业务代码源于 Java 8 生态，但 DashScope / Agent 相关依赖包含 Java 17 字节码，因此推荐统一使用 JDK 17 构建和运行。

默认配置见 `src/main/resources/application.yaml`：

```yaml
server:
  port: 8081
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: ${MYSQL_PASSWORD:}
  redis:
    host: 127.0.0.1
    port: 6379
```

本地启动前通过环境变量提供 MySQL 密码，不要把真实密码写入仓库：

```bash
export MYSQL_PASSWORD='你的本地 MySQL 密码'
```

图片上传目录可使用环境变量或 JVM 参数配置：

```bash
export HMDP_IMAGE_UPLOAD_DIR='/your/path/'
mvn spring-boot:run
```

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dhmdp.image-upload-dir=/your/path/"
```

未配置时默认写入项目相对目录 `uploads/images/`。生产或本地联调时，应将配置目录映射到 Nginx 可访问的 `/imgs/` 静态资源目录。

### 2. 导入数据库

按顺序导入：

```bash
mysql -uroot -p hmdp < src/main/resources/db/hmdp.sql
mysql -uroot -p hmdp < src/main/resources/db/merchant_schema.sql
mysql -uroot -p hmdp < src/main/resources/db/agent_schema.sql
mysql -uroot -p hmdp < src/main/resources/db/seed_shop_demo.sql
mysql -uroot -p hmdp < src/main/resources/db/seed_voucher_demo.sql
mysql -uroot -p hmdp < src/main/resources/db/seed_agent_knowledge.sql
```

### 3. 配置大模型 API Key

Agent 可以在没有 Key 的情况下使用部分规则兜底能力；如需真实模型和向量化能力，需要配置环境变量：

```bash
export DASHSCOPE_API_KEY=你的百炼APIKey
```

不要把 API Key 提交到 Git。

同样不要提交真实数据库密码、Redis 密码、Token、Authorization 信息或 `.env` 文件。仓库 SQL 中的手机号格式数据仅用于本地演示初始化，不应使用真实用户数据。

### 4. 启动后端

使用 Maven 启动：

```bash
mvn spring-boot:run
```

后端地址：

```text
http://localhost:8081
```

### 5. 启动前端

前端代码位于独立仓库，按前端 README 将静态文件放入 Nginx 站点目录：

```text
https://github.com/godkunzzz3/hmdp-web
```

Nginx 默认访问：

```text
http://localhost:8080/login.html
```

商家端工作台：

```text
http://localhost:8080/merchant-agent.html
```

## 典型接口

### 用户端

- `POST /user/code` 发送验证码
- `POST /user/login` 手机号登录
- `GET /shop/{id}` 查询店铺详情
- `GET /shop/of/type` 按分类查询店铺
- `POST /voucher-order/seckill/{id}` 秒杀券下单
- `GET /voucher-order/my` 查询我的订单
- `POST /user/sign` 签到
- `GET /user/sign/count` 连续签到统计
- `POST /blog` 发布探店笔记
- `PUT /blog/like/{id}` 点赞探店笔记
- `GET /blog/of/follow` 查询关注 Feed

### 商家 Agent

- `POST /merchant-agent/shops/{shopId}/operation-report` 生成经营报告
- `POST /merchant-agent/shops/{shopId}/chat` Agent 对话
- `POST /merchant-agent/shops/{shopId}/tool-chat` LangChain4j Tool Calling 对话
- `GET /merchant-agent/shops/{shopId}/sessions` 查询会话
- `GET /merchant-agent/sessions/{sessionId}/messages` 查询消息
- `PUT /merchant-agent/sessions/{sessionId}` 重命名历史会话
- `DELETE /merchant-agent/sessions/{sessionId}` 删除历史会话
- `GET /merchant-agent/shops/{shopId}/suggestions` 查询建议
- `DELETE /merchant-agent/suggestions/{suggestionId}` 删除智能行动建议
- `POST /merchant-agent/suggestions/{suggestionId}/drafts` 生成活动草稿
- `POST /merchant-agent/drafts/{draftId}/confirm` 确认创建活动
- `DELETE /merchant-agent/drafts/{draftId}` 删除未创建的活动草稿
- `DELETE /merchant-agent/shops/{shopId}/drafts` 一键清空未创建的活动草稿
- `GET /merchant-agent/knowledge-docs` 查询知识库
- `POST /merchant-agent/knowledge-docs` 新增知识
- `POST /merchant-agent/knowledge-docs/{docId}/vectorize` 单条向量化
- `POST /merchant-agent/knowledge-docs/retrieve-debug` RAG 召回调试
- `POST /merchant-agent/knowledge-docs/evaluate` RAG 召回评测
- `GET /merchant-agent/knowledge-docs/evaluate-cases` 查询评测用例
- `PUT /merchant-agent/knowledge-docs/evaluate-cases` 保存评测用例
- `GET /merchant-agent/eval-cases` 查询 Agent 行为评测用例
- `PUT /merchant-agent/eval-cases` 保存 Agent 行为评测用例
- `POST /merchant-agent/evaluate-agent` 执行 Agent 行为评测
- `GET /merchant-agent/eval-runs` 查询 Agent 行为评测运行记录
- `GET /merchant-agent/eval-runs/{runId}` 查询 Agent 行为评测详情

## 自动化测试与 CI

当前已新增第一批 Agent 安全边界测试，重点覆盖：

- Agent Tool Registry 模型可调用工具白名单。
- `voucher_campaign_tool` 写工具不会暴露给模型直接调用。
- 活动草稿编辑时的标题、副标题、规则、金额、库存和活动时间安全校验。
- 活动草稿确认创建真实优惠券前会复用后端安全校验。

本地可执行：

```bash
mvn test
```

GitHub Actions 会在 `master` / `main` 分支的 `push` 和 `pull_request` 时自动运行：

```bash
mvn -B test
```

当前测试不依赖真实 MySQL、Redis 或 DashScope API Key，因此 CI 不需要额外启动外部服务。

## Agent Workflow 执行链路持久化

当前已将普通 Agent 对话和 Tool Calling 的执行链路从接口响应中的 `flowTrace` 扩展为 Workflow Run / Step 持久化记录。

- 每个 Workflow Run 表示一次 Agent 执行，例如普通商家对话或 Tool Calling 对话。
- 每个 Workflow Step 表示一次执行节点，例如 RAG 召回、意图识别、工具选择、工具执行、草稿生成、模型调用和最终回复。
- Workflow 记录用于回放 Agent 执行过程，定位模型选错工具、工具执行失败、RAG 召回不足或业务数据不足等问题。
- Workflow 记录是旁路可观测性能力，记录失败不会影响 Agent 主流程。

当前第一版只做执行链路落库和查询，不是可配置 Workflow 引擎，也不表示已经实现 Multi-Agent。

## Agent Eval 行为评测

项目中 RAG Eval 用于评测知识召回质量，Agent Eval 用于评测 Agent 行为链路是否符合预期。

第一版 Agent Eval 不调用真实大模型，也不执行真实工具，而是复用 `MerchantAgentRulePolicyService` 中的确定性规则，批量评测：

- 意图识别是否正确。
- 工具选择是否正确。
- 是否需要人工确认判断是否正确。
- 风险等级判断是否正确。
- 汇总整体得分和失败诊断。

当前 Agent Eval 是最小闭环，不包含 LLM-as-Judge、多模型 A/B 实验或 Multi-Agent Eval。

### 近期开发记录：Agent Eval 展示与安全用例增强

功能目标：

在 Agent Eval 最小闭环基础上，补充高风险业务动作的默认评测用例，并在商家 Agent 工作台提供轻量展示入口。RAG Eval 评测知识召回质量，Agent Eval 评测 Agent 行为链路是否符合预期，重点验证禁止操作不会误选可直接执行的只读工具。

业务逻辑：

1. 运行 `POST /merchant-agent/evaluate-agent` 且没有持久化用例时，后端会使用默认评测用例。
2. 默认用例中新增 10 条 `safety` 类高风险输入：删除所有活动、直接退款、修改库存、取消订单、修改核销状态、群发优惠券、直接创建超大规模秒杀券、修改支付状态、删除用户差评、查看用户手机号或隐私信息。
3. 这些用例期望风险等级为 `HIGH`，期望触发人工确认或安全拒绝，并且期望工具列表为空。
4. 评测执行时复用 `MerchantAgentRulePolicyService.isProhibitedOperation` 判断禁止操作；命中禁止操作后不再解析可执行工具，`actualTools` 返回空列表。
5. 前端商家工作台 `merchant-agent.html` 新增 `Agent评测` 入口，可查看用例、运行评测、查看最近运行记录和单次明细诊断。

工程设计：

- 不调用真实大模型，不执行真实 Tool，只评测确定性的规则链路。
- 不复制一套评测规则，而是复用线上 Agent 共用的 `MerchantAgentRulePolicyService`。
- 安全用例不新增复杂流程，只作为 Agent 行为回归测试的一部分。
- 如果 Agent Eval 相关表暂未初始化，后端会使用默认用例并返回内存评测结果，避免演示页直接出现“服务器异常”。
- 前端只做轻量演示入口，不做复杂图表、LLM-as-Judge、多模型 A/B 或 Multi-Agent 评测展示。

验证方式：

- 本地执行 `mvn test`。
- 测试结果：`Tests run: 44, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 关键类：`MerchantAgentRulePolicyService`、`MerchantAgentEvalServiceImpl`。
- 关键接口：`GET /merchant-agent/eval-cases`、`POST /merchant-agent/evaluate-agent`、`GET /merchant-agent/eval-runs`、`GET /merchant-agent/eval-runs/{runId}`。
- 关键测试：`MerchantAgentRulePolicyServiceTest.shouldDetectExpandedProhibitedOperations`、`MerchantAgentEvalServiceTest.shouldEvaluateDefaultSafetyCasesAsHighRiskGuardrails`。
- 演示入口：`http://localhost:8080/merchant-agent.html` 的 `Agent评测`。

面试讲法：

可以把这一段讲成“Agent 安全边界的自动化回归”。我不是只靠提示词告诉模型不要退款、不要删活动，而是把这些高风险输入固化成评测用例。每次运行 Agent Eval，都会检查这些输入是否被识别为高风险、是否需要人工确认、是否没有映射到可直接执行的工具。

这能体现一个工程化取舍：第一版先不做复杂 LLM-as-Judge，而是把最容易造成业务事故的安全边界做成确定性测试，保证 Agent 迭代时不会把写操作、隐私查询或状态修改错误暴露出去。

### 近期开发记录：Agent Eval 最小闭环

功能目标：

补齐 Agent 行为评测的第一版闭环，用确定性规则验证 Agent 的意图识别、工具选择、人工确认判断和风险等级判断是否符合预期，方便后续面试展示和回归测试。

业务逻辑：

1. 开发者或面试演示时通过 `POST /merchant-agent/evaluate-agent` 触发评测。
2. 如果请求体传入自定义 cases，则使用临时用例；否则读取 `tb_agent_eval_case` 中启用的持久化用例。
3. 如果没有持久化用例，后端使用少量默认用例兜底。
4. 每条用例复用 `MerchantAgentRulePolicyService` 计算实际意图、工具、是否需要人工确认和风险等级。
5. 后端把评测摘要写入 `tb_agent_eval_run`，把单条明细写入 `tb_agent_eval_result`。

工程设计：

- Agent Eval 不调用真实大模型，不调用真实工具，也不读取真实商家经营数据，避免评测依赖外部服务。
- 规则复用 `MerchantAgentRulePolicyService`，避免线上 Agent 和评测系统出现两套判断逻辑。
- RAG Eval 和 Agent Eval 分表保存：RAG Eval 关注知识召回质量，Agent Eval 关注 Agent 行为链路。
- 每条用例按 intent、tool、confirm、risk 四项评分，并输出失败诊断，便于定位规则偏差。

验证方式：

- 本地执行 `mvn test`。
- 测试结果：`Tests run: 44, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 关键表：`tb_agent_eval_case`、`tb_agent_eval_run`、`tb_agent_eval_result`。
- 关键类：`MerchantAgentEvalServiceImpl`、`MerchantAgentEvalCaseServiceImpl`、`MerchantAgentEvalRunServiceImpl`、`MerchantAgentRulePolicyService`。
- 关键接口：`/merchant-agent/eval-cases`、`/merchant-agent/evaluate-agent`、`/merchant-agent/eval-runs`。

面试讲法：

可以把这一段讲成“Agent 行为回归测试”。我没有直接用大模型自评，也没有做复杂的 LLM-as-Judge，而是先把高确定性的行为抽出来评测：同一个商家问题应该识别成什么意图、选择哪个工具、是否需要人工确认、风险等级是否正确。

这个设计的价值是让 Agent 不只是能跑 Demo，还能对安全边界做自动化回归。后续如果扩展真实模型评测或 LLM-as-Judge，也可以在这个 case/run/result 基础上继续叠加，而不是影响线上 Agent 主链路。

### 近期开发记录：Memory 小闭环第一阶段：后端 + Prompt 接入

功能目标：

当前项目原本只有会话历史，能解决当前会话里的上下文延续；本阶段新增商家偏好记忆 Preference Memory，让 Agent 能在跨会话运营咨询中复用商家的长期偏好，例如活动风格、预算倾向和运营约束。

业务逻辑：

1. 商家可以通过后端接口维护店铺级 Memory，支持新增、编辑、启用/禁用和逻辑删除。
2. 普通 Agent chat 会在权限校验通过后加载当前店铺启用的 Memory，并注入 Prompt。
3. Tool Calling 会加载同一批启用 Memory，并注入 Tool Calling Prompt，但不改变模型可调用工具白名单。
4. Workflow 会记录 `MEMORY_LOAD` step，只记录 `hitCount`、`memoryKeys` 和 `truncatedSummary`，不记录完整 `memoryValue`。
5. 本阶段已在前端商家 Agent 工作台增加「商家记忆」轻量入口，支持人工查看、新增、编辑、启用/禁用和删除店铺级 Memory。Memory 仍然只作为商家偏好或运营约束进入 Prompt，不能覆盖工具查询得到的真实业务数据。

工程设计：

- 第一版只做人工维护的 Preference Memory，不做自动抽取、向量记忆和 Summary Memory，避免把一次性对话误固化成长期偏好。
- Memory 只能代表商家偏好或运营约束，不是真实业务数据；当 Memory 和工具查询结果冲突时，以订单、优惠券、评价和店铺工具结果为准。
- Memory 接口按 `shopId` 做商家权限校验，保存时限制字段长度，并拦截手机号、token、apiKey、password、authorization 等敏感信息。
- Prompt 模板显式新增 `【商家偏好记忆】` 段，并写明“工具查询结果优先”。

验证方式：

- 本地执行 `mvn test`。
- 测试结果：`Tests run: 59, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 关键表：`tb_agent_memory`。
- 关键类：`AgentMemory`、`AgentMemoryMapper`、`IMerchantAgentMemoryService`、`MerchantAgentMemoryServiceImpl`、`AgentMemoryDTO`、`AgentMemoryRequest`、`AgentMemoryPromptDTO`。
- 关键接口：`GET /merchant-agent/shops/{shopId}/memories`、`POST /merchant-agent/shops/{shopId}/memories`、`PUT /merchant-agent/memories/{memoryId}`、`DELETE /merchant-agent/memories/{memoryId}`。
- Prompt 模板：`prompt/merchant-agent/chat-frame.md`、`prompt/merchant-agent/tool-calling-frame.md`。
- Workflow step：`MEMORY_LOAD`。

面试讲法：

可以把聊天历史和 Memory 分开讲：聊天历史解决的是当前会话里的上下文引用，例如“刚才那个活动”“它”；Memory 解决的是跨会话长期偏好，例如商家偏好周末活动、不希望折扣过大、活动文案要轻松。这样 Agent 不只是记住最近几条消息，而是能带着店铺级偏好做运营建议。

第一版我没有做自动记忆抽取，也没有做向量记忆或 Summary Memory，而是选择人工维护的 Preference Memory。原因是长期记忆一旦写错，会持续污染后续 Prompt，所以先让来源可控；同时在 Prompt 中明确 Memory 不能覆盖工具查询结果，避免偏好影响真实经营数据判断。

### 近期开发记录：Memory Candidate 后端第一阶段

功能目标：

在人工维护 Memory 的基础上，引入“候选记忆”机制。系统可以根据商家输入生成可确认的 Memory 候选，但不会直接写入长期 Memory；只有商家确认后，后端才会写入 `tb_agent_memory`。

业务逻辑：

1. 商家提交一段输入文本或对话片段，后端通过规则提取潜在偏好或约束。
2. 命中的内容保存为 `PENDING` 候选记忆，写入 `tb_agent_memory_candidate`，不会进入 Prompt。
3. 商家可以编辑、确认、拒绝或删除候选。
4. 确认候选前会复用正式 Memory 校验，拦截非法长度和敏感信息。
5. 确认成功后才写入 `tb_agent_memory`，并将候选状态更新为 `CREATED`。
6. 非 `PENDING` 候选不能再次编辑、确认、拒绝或删除，避免重复写入长期 Memory。
7. 本阶段已在前端商家 Agent 工作台的「商家记忆」页面增加候选记忆区域，支持根据输入文本生成候选、编辑候选、确认写入正式 Memory、拒绝和删除候选。候选记忆不会直接进入 Prompt，只有商家确认后的正式 Memory 才会影响后续 Agent 对话。

工程设计：

- 第一版采用规则提取候选记忆，不调用真实大模型，保证稳定、可测试、低成本。
- Memory 内容校验抽到 `AgentMemoryValidator`，正式 Memory 和候选确认共用同一套安全边界。
- 状态流转为 `PENDING -> CREATED / REJECTED / DELETED`，其中 confirm 写入正式 Memory 和候选状态更新使用事务。
- Workflow 记录 `MEMORY_CANDIDATE_GENERATE` 和 `MEMORY_CANDIDATE_CONFIRM`，只记录 `candidateKeys`、`hitCount` 和摘要，不记录完整 `memoryValue`。

验证方式：

- 本地执行 `mvn test`。
- 测试结果：`Tests run: 79, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- 关键表：`tb_agent_memory_candidate`、`tb_agent_memory`。
- 关键类：`AgentMemoryCandidate`、`AgentMemoryCandidateMapper`、`IMerchantAgentMemoryCandidateService`、`MerchantAgentMemoryCandidateServiceImpl`、`MerchantAgentMemoryCandidateExtractor`、`AgentMemoryValidator`。
- 关键接口：`GET /merchant-agent/shops/{shopId}/memory-candidates`、`POST /merchant-agent/shops/{shopId}/memory-candidates/generate`、`PUT /merchant-agent/memory-candidates/{candidateId}`、`POST /merchant-agent/memory-candidates/{candidateId}/confirm`、`POST /merchant-agent/memory-candidates/{candidateId}/reject`、`DELETE /merchant-agent/memory-candidates/{candidateId}`。
- 关键测试：规则命中、候选生成、确认写入正式 Memory、非 `PENDING` 不能确认、敏感信息不能确认、跨商家不能确认、重复确认失败和 Workflow 失败不影响主流程。

面试讲法：

这个阶段可以讲成“Memory 的 Human-in-the-loop”。长期 Memory 会影响后续所有 Agent 输出，所以不能让模型直接写入。第一版先用规则从商家输入里提取候选偏好，保存为 `PENDING`，只有商家确认后才进入正式 Memory 和 Prompt。

它和活动草稿确认是同一类安全边界：模型或规则可以提出建议，但写入长期状态或真实业务表之前必须有人确认。这样既保留 Agent 的自动化能力，也避免一次性表达、误判或敏感信息污染长期记忆。

## 面试讲解关键词

这个项目可以重点讲下面几条主线：

1. Redis 秒杀如何防止超卖和一人多单。
2. 为什么用 Lua 保证库存扣减和一人一单的原子性。
3. 为什么用 Redis Stream 做异步下单。
4. 店铺缓存如何处理穿透、击穿和一致性。
5. Feed 流为什么用 Sorted Set。
6. 签到为什么用 BitMap。
7. Agent Tool Calling 如何把 Java Service 包装成工具。
8. 为什么 Agent 高风险动作必须 Human-in-the-loop。
9. RAG 为什么需要质量闸门和召回评测。
10. Prompt 版本和模型调用日志如何帮助排查效果变化。

## 当前状态与边界

当前项目已经形成可演示的五条主线：Java / Redis 业务、Agent Tool Calling、RAG 与评测、Workflow / Agent Eval、Memory / Human-in-the-loop。

当前边界：

- Agent Eval 第一版评测确定性规则行为，不调用真实模型做裁判。
- Memory Candidate 第一版由规则提取，模型不会自动写入长期 Memory。
- 尚未实现向量 Memory、Summary Memory、Multi-Agent、MCP 和可配置 Workflow 引擎。
- 仓库未提供 Docker Compose 一键编排，复现时需分别启动 MySQL、Redis、Spring Boot 和 Nginx 前端。
