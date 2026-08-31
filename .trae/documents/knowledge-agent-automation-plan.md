# 知识库 + 智能体占位 + 自动化 执行计划

## 一、摘要

为 agent-platform 增加三大能力：

1. **知识库**：对接 `/Users/whale_fall/developer/python/multimodal-rag` 的 CLI（`multimodal-rag search/ask/chunk/ingest`），后端封装为 `AgentTool` 供 Agent 检索调用，同时提供 REST API + console 管理页面（文档导入、检索/问答测试）。
2. **智能体创建**：本期仅做**占位**（用户决定"先暂时占位"）——前端 Agents 页面预留"创建智能体"入口与说明，不实现动态创建逻辑。
3. **自动化**：参考 TRAE 定时任务的完整闭环——cron 任务 CRUD、到点向指定 Agent 发起一次运行（新会话）、执行历史记录、console 管理页面，持久化跟随现有 `agentos.persistence.mode`（memory|postgresql）。

## 二、现状分析（基于代码探索）

| 关注点 | 现状 | 关键文件 |
|---|---|---|
| 工具接口 | `AgentTool`（name/description/parameters/riskLevel/execute），`ToolRegistry` 注册 | [AgentTool.java](file:///Users/whale_fall/developer/java/agent-platform/agentos-tool/src/main/java/com/github/agentos/tool/api/AgentTool.java)、[ToolRegistry.java](file:///Users/whale_fall/developer/java/agent-platform/agentos-tool/src/main/java/com/github/agentos/tool/runtime/ToolRegistry.java) |
| 工具 Bean 装配 | 内置工具统一在 `AgentOsConfiguration` 中以 `@Bean` 注册，自动进入 `toolRegistry(List<AgentTool>)` | [AgentOsConfiguration.java](file:///Users/whale_fall/developer/java/agent-platform/agentos-server/src/main/java/com/github/agentos/server/config/AgentOsConfiguration.java) |
| Agent 运行入口 | `POST /api/agents/runs`，RunRequest(input/sessionId/agentId 默认 main-agent)，走 `AgentRunner.runDetailed` | [AgentController.java](file:///Users/whale_fall/developer/java/agent-platform/agentos-server/src/main/java/com/github/agentos/server/controller/AgentController.java) |
| 持久化切换 | `PersistenceConfiguration` 按 `agentos.persistence.mode`（postgresql→MyBatis-Plus / memory→内存）装配各 Store；建表脚本 `db/schema-postgresql.sql` | [PersistenceConfiguration.java](file:///Users/whale_fall/developer/java/agent-platform/agentos-server/src/main/java/com/github/agentos/server/config/PersistenceConfiguration.java)、[schema-postgresql.sql](file:///Users/whale_fall/developer/java/agent-platform/agentos-server/src/main/resources/db/schema-postgresql.sql) |
| 文件上传范式 | `AttachmentController` multipart 上传 → 存入 file-access 根目录下子目录 | [AttachmentController.java](file:///Users/whale_fall/developer/java/agent-platform/agentos-server/src/main/java/com/github/agentos/server/controller/AttachmentController.java) |
| console 管理页 | `ManagementView.vue` 的 `pages` computed 按 `route.meta.section` 渲染；侧边栏 `AppSidebar.vue` 的 `managementGroups`；路由在 `router.js` | [ManagementView.vue](file:///Users/whale_fall/developer/java/agent-platform/agentos-console/src/views/ManagementView.vue)、[AppSidebar.vue](file:///Users/whale_fall/developer/java/agent-platform/agentos-console/src/components/AppSidebar.vue) |
| 调度现状 | 无 cron/自动化代码（仅 `MemoryPipeline` 用过 `ScheduledExecutorService`） | — |
| multimodal-rag CLI | 命令 `multimodal-rag`（uv 管理），子命令 `chunk/ingest/search/ask/evaluate/serve`；`search/ask` 支持 `--json --top-k --database-url --access-token`；依赖 `.env`（DATABASE_URL/ACCESS_TOKEN）与 PostgreSQL+pgvector | [cli/main.py](file:///Users/whale_fall/developer/python/multimodal-rag/src/multimodal_rag/cli/main.py) |

**约束提醒**（来自项目记忆）：
- ReactAgent 有 `agentos.agent.react.allowed-tools` 白名单机制——若部署配置了白名单，新工具必须加入白名单才可见。
- 工具风险等级：知识库检索为只读，`riskLevel` 用最低档，无需 HITL 审批。

## 三、改动方案

### Part A：知识库（Tool + 管理页面）

#### A1. CLI 封装工具（后端）

**新文件** `agentos-tool/src/main/java/com/github/agentos/tool/builtin/knowledge/KnowledgeBaseTool.java`
- 实现 `AgentTool`，通过 `ProcessBuilder` 调用 multimodal-rag CLI，工作目录为配置的项目路径。
- 构造参数：`mode`（SEARCH/ASK，决定工具名 `knowledge_search` / `knowledge_ask`）、`CliProcessRunner`、默认 topK。
- `parameters()`：`question`（必填）、`top_k`（可选，默认 5）。
- 执行逻辑：拼命令 `uv run multimodal-rag search|ask "<question>" --json [--top-k N]`，附加环境变量 `MULTIMODAL_RAG_ACCESS_TOKEN`（若配置），超时（默认 120s）销毁进程并返回错误文本；解析 stdout JSON（`--json` 输出）作为工具结果返回，非零退出码返回 stderr 摘要。
- `riskLevel()`：只读档（与 `web_search` 同级）。

**新文件** `agentos-tool/src/main/java/com/github/agentos/tool/builtin/knowledge/KnowledgeCliProperties.java`（或直接在配置类中用 `@Value`）
- 配置项（加入 `application.yml` 与 `application-example.yml`）：

```yaml
agentos:
  knowledge:
    enabled: false            # 默认关闭，避免无 CLI 环境报错
    work-dir: /Users/whale_fall/developer/python/multimodal-rag
    cli-command: "uv run multimodal-rag"
    access-token: ""          # 留空则依赖 work-dir 下 .env
    timeout-seconds: 120
    top-k: 5
```

**修改** [AgentOsConfiguration.java](file:///Users/whale_fall/developer/java/agent-platform/agentos-server/src/main/java/com/github/agentos/server/config/AgentOsConfiguration.java)
- 增加 `@Bean @ConditionalOnProperty("agentos.knowledge.enabled")` 注册 `knowledgeSearchTool`、`knowledgeAskTool` 两个 Bean（同一实现类不同 mode）。

**修改** `application.yml` / `application-example.yml`：加入上述配置段（主配置 `enabled: false`，example 给出说明）。

#### A2. 知识库管理 REST API（后端）

**新文件** `agentos-server/src/main/java/com/github/agentos/server/controller/KnowledgeBaseController.java`（`@RequestMapping("/api/knowledge")`）
- `POST /api/knowledge/search`：body `{question, topK}` → 调 CLI search --json，返回解析后的检索结果。
- `POST /api/knowledge/ask`：body `{question, topK}` → 调 CLI ask --json。
- `POST /api/knowledge/ingest`：multipart `file` → 存到 `<file-access根>/knowledge-inbox/<uuid>-<filename>`（复用 AttachmentController 的存储范式）→ 顺序执行 `chunk <file> --output <tmp-dir>` 与 `ingest <tmp-dir>` → 返回两步的 JSON 输出（含 stats）。
- `GET /api/knowledge/status`：返回 enabled、work-dir、CLI 是否可用（执行一次 `--help` 探测，缓存结果）。
- 全部复用 A1 的 `CliProcessRunner`（放在 agentos-tool 的 knowledge 包内，server 直接引用）。
- 同样 `@ConditionalOnProperty("agentos.knowledge.enabled")` 装配；未启用时端点不存在（前端据 status 显示未启用提示）。

#### A3. console 知识库管理页面（前端）

**新文件** `agentos-console/src/components/KnowledgePanel.vue`
- 三块区域：① 文档导入（文件选择 + 上传按钮 + 导入结果 stats 展示）；② 检索测试（问题输入 + top-k + 结果列表）；③ 问答测试（问题输入 + 答案与引用展示）。
- 调用 `consoleApi.js` 新增的 `knowledgeSearch/knowledgeAsk/knowledgeIngest/knowledgeStatus` 函数。

**修改** [ManagementView.vue](file:///Users/whale_fall/developer/java/agent-platform/agentos-console/src/views/ManagementView.vue)
- `pages` computed 增加 `knowledge: { index: '14', title: 'Knowledge', kicker: 'RETRIEVAL BASE', description: ... }`。
- 模板中 `section === 'knowledge'` 时渲染 `<KnowledgePanel />`。

**修改** [AppSidebar.vue](file:///Users/whale_fall/developer/java/agent-platform/agentos-console/src/components/AppSidebar.vue)
- `managementGroups` 的"能力"分组追加 `['/knowledge', { zh: '知识库', en: 'Knowledge' }, 'knowledge']`，并在 `icons` 中补对应图标 path。

**修改** [router.js](file:///Users/whale_fall/developer/java/agent-platform/agentos-console/src/router.js)
- 增加 `/knowledge` 路由，`meta.section = 'knowledge'`。

### Part B：智能体创建（占位）

用户决定本期占位，不实现动态创建：

**修改** [ManagementView.vue](file:///Users/whale_fall/developer/java/agent-platform/agentos-console/src/views/ManagementView.vue)
- Agents section 页头增加"创建智能体"按钮，点击弹出提示框："动态创建即将上线，当前可通过 `agentos.agents.config-file` 指向的 YAML 文件定义 Agent（重启生效）"。
- 不做后端 stub、不建表，避免过度设计。计划文档中记录未来扩展点：`POST /api/console/agents` + 动态注册进 `AgentRegistry`/`ToolRegistry`。

### Part C：自动化（完整闭环）

#### C1. 领域模型与存储（后端，放 agentos-server）

**新包** `com.github.agentos.server.automation`：
- `Automation.java`（record）：`id, name, cronExpression(5段), timezone, agentId, message, enabled, createdAt, updatedAt`。
- `AutomationRun.java`（record）：`id, automationId, triggeredAt, sessionId, status(SUCCESS|FAILED|RUNNING), errorMessage, finishedAt`。
- `AutomationStore.java`（接口）：`save/findById/delete/all/updateEnabled/appendRun/runsOf(automationId, limit)`。
- `InMemoryAutomationStore.java`：ConcurrentHashMap 实现。
- `MybatisAutomationStore.java` + `persistence/mybatis` 下新增 `AutomationEntity/AutomationRunEntity` 与两个 Mapper（跟随现有 `PostgresqlMapperConfiguration` 的 `@MapperScan` 包）。

**修改** [schema-postgresql.sql](file:///Users/whale_fall/developer/java/agent-platform/agentos-server/src/main/resources/db/schema-postgresql.sql)
- 追加 `agentos_automation`（id PK, name, cron_expression, timezone, agent_id, message, enabled, created_at, updated_at）与 `agentos_automation_run`（id PK, automation_id, triggered_at, session_id, status, error_message, finished_at）两张表。

**修改** [PersistenceConfiguration.java](file:///Users/whale_fall/developer/java/agent-platform/agentos-server/src/main/java/com/github/agentos/server/config/PersistenceConfiguration.java)
- 按现有模式增加 `automationStore` Bean：postgresql → `MybatisAutomationStore`，否则 `InMemoryAutomationStore`。

#### C2. 调度器（后端）

**新文件** `automation/AutomationScheduler.java`
- 持有 `ThreadPoolTaskScheduler`（Bean，pool size 2）与 `Map<String, ScheduledFuture<?>>`。
- cron 处理：用户输入标准 5 段 cron，校验用 Spring `CronExpression.parse("0 " + cron)`（前置补秒位）；调度用 `CronTrigger` 并携带 `timezone`（默认 Asia/Shanghai）。
- `reschedule(Automation)` / `cancel(id)` / `rescheduleAll()`（`SmartInitializingSingleton` 启动时对所有 enabled 任务 reschedule）。
- 触发逻辑：新建会话 `sessionId = "automation-" + id + "-" + 时间戳`，构造与 `AgentController.normalize` 相同语义的 `AgentRequest`（agentId 来自任务定义，默认 `main-agent`；input = message），调用 `AgentRunner` 同步执行；执行前后写 `AutomationRun` 记录（RUNNING→SUCCESS/FAILED）。

**新文件** `automation/AutomationService.java`
- CRUD 编排：创建/更新后 `store.save` + `scheduler.reschedule`；删除时 `cancel` + `store.delete`；启用/停用切换 `enabled` 并 reschedule/cancel；`triggerNow(id)` 手动触发一次（复用调度器的触发逻辑，异步执行并落 run 记录）。

#### C3. REST API（后端）

**新文件** `automation/AutomationController.java`（`@RequestMapping("/api/automations")`）
- `GET /`：列表（含每条任务最近一次 run 摘要）。
- `POST /`：创建（校验 cron 合法性、name 非空、agentId 存在性提示但不强校验）。
- `PUT /{id}`：更新；`DELETE /{id}`：删除；`POST /{id}/toggle`：启用/停用；`POST /{id}/trigger`：手动触发。
- `GET /{id}/runs?limit=20`：执行历史。

#### C4. console 自动化页面（前端）

**新文件** `agentos-console/src/components/AutomationPanel.vue`
- 任务列表（名称、cron、目标 Agent、启用状态、上次运行结果）；创建/编辑表单（name、cron、timezone 默认 Asia/Shanghai、agentId 下拉取自 catalog agents、message 多行文本）；启用/停用开关、手动运行、删除（复用现有确认弹窗样式）；点击任务展开执行历史。
- `consoleApi.js` 新增 `getAutomations/createAutomation/updateAutomation/deleteAutomation/toggleAutomation/triggerAutomation/getAutomationRuns`。

**修改** `ManagementView.vue`：`pages` 增加 `automations: { index: '15', title: 'Automations', kicker: 'SCHEDULED RUNS', ... }`，模板渲染 `<AutomationPanel />`。
**修改** `AppSidebar.vue`：运行分组追加 `['/automations', { zh: '自动化', en: 'Automations' }, 'automations']` + 图标。
**修改** `router.js`：`/automations` 路由，`meta.section = 'automations'`。

## 四、假设与决策

1. **CLI 调用方式**：用 `uv run multimodal-rag`（项目由 uv 管理），工作目录设为 multimodal-rag 项目根，使其自动读取该项目 `.env`；access-token 可通过配置注入环境变量覆盖。
2. **知识库默认关闭**（`agentos.knowledge.enabled=false`），避免未安装 CLI/数据库的部署环境启动报错；本机开发在 plist/环境变量中开启。
3. **智能体创建仅前端占位**，不做后端 stub、不建表（用户明确"先占位"）。
4. **自动化 cron 采用标准 5 段格式**，时区字段默认 `Asia/Shanghai`；不做子分钟级与一次性任务（对齐 TRAE 语义）。
5. **自动化触发走同步 `AgentRunner`**，在调度线程池中异步执行，不阻塞调度；每次触发使用全新 sessionId，不携带历史会话。
6. **不引入新依赖**：调度用 Spring 自带 `ThreadPoolTaskScheduler`/`CronExpression`；MyBatis-Plus 已存在。
7. 知识库导入的中间产物（chunk 输出目录）放在系统临时目录，ingest 完成后删除。

## 五、验证步骤

1. **单元测试**（新增）：
   - `KnowledgeBaseToolTest`：mock CliProcessRunner，验证命令拼接、JSON 解析、超时/非零退出处理。
   - `AutomationSchedulerTest`：验证 5 段 cron 解析、非法 cron 拒绝、reschedule/cancel。
   - `InMemoryAutomationStoreTest`、`AutomationServiceTest`（CRUD + toggle 联动调度）。
   - 前端：`npm run test`（console 现有 vitest 体系）为 AutomationPanel/KnowledgePanel 的 API 调用补基础用例。
2. **全量构建**：`mvn -q test` 通过；`agentos-console` 下 `npm run build` 通过。
3. **本地端到端**（memory 模式启动）：
   - `curl POST /api/knowledge/search` 验证 CLI 链路（需本机已装 uv 且 multimodal-rag .env 可用）。
   - `curl` 自动化 CRUD：创建每分钟级测试任务 → 观察到点产生 `automation-*` 会话运行 → `GET /{id}/runs` 有记录 → 手动 trigger → 停用后不再触发 → 删除。
   - 打开 console `/knowledge` 与 `/automations` 页面，走一遍导入/检索、创建/触发/历史流程。
4. **postgresql 模式回归**：以 `agentos.persistence.mode=postgresql` 启动，确认新表创建成功、自动化数据落库（本机若无 PG 环境则以 Mybatis Store 的单测覆盖）。

## 六、实施顺序

1. Part A1（CLI 工具 + 配置）→ A2（REST API）
2. Part C1–C3（自动化后端：模型/存储/调度/API）
3. Part A3 + C4 + B（前端：知识库面板、自动化面板、智能体占位）
4. 测试与验证
