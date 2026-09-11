# agentos-server

`agentos-server` 是 AgentOS 的 Spring Boot 启动与适配层，负责组装所有模块、提供 HTTP API，并生成可独立运行的应用程序。

## 主要职责

- 启动 Spring Boot Web 应用。
- 将路由、规划器、工具、记忆、审批服务、主 Agent 和运行时装配为 Bean。
- 提供创建 Agent 运行和查询会话状态的 REST API。
- 提供 Planner、Tool、Observation、Decision 阶段的 SSE 流式运行 API。
- 提供按会话或 Invocation 查询领域事件轨迹的只读 API。
- 提供按作用域查看 L0-L3 数据的只读记忆管理 API。
- 提供会话产物（Artifact）的列举、下载与删除 API。
- 提供按会话汇总的模型 token 用量查询 API。
- 提供按 Invocation 的工具轨迹评估 API（期望路径、禁用工具、预算、回答关键词）。
- 将请求参数错误转换为标准 HTTP Problem Detail 响应。
- 承载跨模块集成测试和可执行 JAR 打包。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `AgentOsApplication` | 标注 `@SpringBootApplication` 的应用入口。 |
| `AgentOsConfiguration` | 装配工具、记忆、审批、计划执行器、主 Agent、插件、产物存储和运行时。 |
| `LlmPlannerConfiguration` | 装配 `LlmAgentPlanner` 和默认的 OpenAI-compatible `ModelClient` / `ChatClient`。 |
| `RoutingConfiguration` | 装配三级意图路由（问候白名单 → 简单 QA → PlanExecuteAgent）。 |
| `PersistenceConfiguration` | 按 `agentos.persistence.mode` 装配内存或 SQLite 持久化实现。 |
| `SecurityConfiguration / ApiKeyAuthFilter` | `X-API-Key` 请求头鉴权；未配置密钥时本地开发自动关闭。 |
| `AgentController` | 暴露 Agent 运行和状态查询 API。 |
| `BackgroundAgentRunController` | 暴露后台运行创建、快照、事件补播和取消 API。 |
| `ArtifactController` | 暴露会话产物列举、下载与删除 API。 |
| `AgentEventController` | 暴露按会话/Invocation 分组的领域事件轨迹查询 API。 |
| `SessionController` | 暴露会话列表与单会话状态快照查询 API。 |
| `EvaluationController / EvaluationService` | 回放 Invocation 事件流并按评估用例比对工具轨迹。 |
| `ConsoleCatalogController` | 为 Console 管理面板提供不含密钥的运行时只读目录（`/api/console/catalog`、`/api/console/agents/{id}`）。 |
| `SkillConfiguration` | 装配技能注册表与 `load_skill` 工具；本地目录优先于 classpath 内置技能。 |
| `CodeExecutorConfiguration` | 按 mode 装配代码执行器（docker 沙箱 / 本地进程 / auto）与 `execute_code` 工具。 |
| `UsageController / UsageRecorder` | 模型 token 用量记账与按会话查询。 |
| `SessionHistoryService` | 运行前回放最近轮次并注入 QA 与规划 prompt 的多轮上下文。 |
| `AgentRunCoordinator` | 管理进程内后台 Run、事件序号和 SSE 订阅者。 |
| `MemoryController` | 暴露 L0-L3 记忆快照只读查询 API。 |
| `AgentExceptionHandler` | 将非法参数异常转换为 HTTP 400。 |

## 默认装配

```text
AgentRunner
    └── RoutingAgentLoop（问候白名单 / simple-qa-agent / PlanExecuteAgent）
        └── PlanExecuteAgent
            ├── AgentPlanner
            │   └── LlmAgentPlanner ──► ModelClient（经 LlmFlow 组装请求）
            ├── PlanExecutor
            │   ├── FailureClassifier
            │   └── ToolDispatcher
            │       ├── ToolRegistry ──► directory_list / file_search / file_read / file_write /
            │       │                  run_command / browser_search / web_fetch / web_crawl / today /
            │       │                  load_skill / execute_code / ...
            │       └── ApprovalToolInterceptor ──► RiskPolicy / ApprovalService
            ├── AgentFinalizer
            ├── ContinuationStore（断点续跑）
            └── MemoryService
                ├── MemoryStore (memory / file / sqlite)
                ├── MemoryModel (rules / OpenAI-compatible)
                └── MemoryEmbedding (hashing / OpenAI-compatible)

横切：AgentPluginManager（用量记账等插件） · ArtifactService（产物存储） ·
      AgentEventStore / CheckpointStore / SessionService（memory 或 sqlite）
```

## HTTP API

### 创建一次运行

```http
POST /api/agents/runs
Content-Type: application/json

{
  "agentId": "plan-execute-agent",
  "sessionId": "session-1",
  "input": "hello agentos",
  "attributes": {}
}
```

`agentId` 和 `sessionId` 可以省略，服务会分别使用默认 Agent ID 和自动生成的会话 ID。

### 查询会话状态

```http
GET /api/agents/{sessionId}/state
```

会话不存在时返回 `404 Not Found`。

### 可恢复的后台运行

控制台默认使用与连接生命周期解耦的后台运行接口：

```http
POST /api/agent-runs
Content-Type: application/json

{"sessionId":"session-1","input":"阅读当前项目并总结功能"}
```

创建接口返回 `202 Accepted` 和 `runId`。随后可列出全部运行、查询快照、从指定游标补播事件或显式取消：

```http
GET  /api/agent-runs
GET  /api/agent-runs/{runId}
GET  /api/agent-runs/{runId}/events?afterSeq=42
GET  /api/agent-runs/history?sessionId=session-1
POST /api/agent-runs/{runId}/cancel
```

列表按创建时间倒序返回当前用户的持久化运行台账。事件统一使用 `AgentStreamEvent`，包含
`schemaVersion`、全局唯一 `eventId` 和 Run 内单调递增 `seq`，SSE 的 `id` 与 `seq` 一致。
`status` 和文本 delta 只实时发送，消息最终正文、工具生命周期、产物、用量与 Run 生命周期会持久化。
断开事件连接不会取消任务；
重新连接时传入最后成功处理的序号即可补播遗漏事件。`cancel` 触发协作式取消令牌，
运行中的循环与工具在下一个检查点进入 `CANCELLED` 终态。

旧的 `/api/agents/runs/stream` 与 `/api/agents/runs/event-stream` 已移除；客户端不得依赖模型厂商原始流格式。
详见 [`AgentStreamEvent 流式协议`](../docs/AGENT_CONTROLLER_RUNS_STREAM_FLOW.md)。

### 查询与下载产物

文件类工具写入成功后自动登记为会话产物：

```http
GET    /api/artifacts?sessionId=session-1        列举（按登记时间倒序）
GET    /api/artifacts/{artifactId}               下载原始内容（带 MIME 与 attachment 头）
DELETE /api/artifacts/{artifactId}               删除
```

不再向调用方暴露本地文件路径；产物目录由 `agentos.artifacts.root` 配置。

### 查询模型用量

```http
GET /api/usage/{sessionId}
```

返回该会话累计的模型调用次数与 prompt/completion/total token 数。数据经
`AgentPluginManager` 在每次模型回调点记账，进程重启后在 sqlite 模式下仍可查询。

### 评估一次运行

对任意一次 Invocation 提交评估用例，校验 Agent 的工具轨迹与最终回答：

```http
POST /api/evaluations/{invocationId}
Content-Type: application/json

{
  "caseId": "search-then-answer",
  "expectedToolSequence": ["browser_search"],
  "forbiddenTools": ["file_write"],
  "maxToolCalls": 5,
  "requiredResponseKeywords": ["结论"],
  "requireCompleted": true
}
```

响应包含 `passed`、`score`（通过检查数 / 已执行检查数）、逐项 `findings` 明细、
实际工具序列与最终回答。所有字段均可省略；Invocation 无事件记录时返回 `404`。
评估语义详见 [`agentos-kernel`](../agentos-kernel/README.md)。

### Console 管理面板目录

```http
GET /api/console/catalog
GET /api/console/agents/{agentId}
```

为 Console 管理面板提供运行时只读快照，响应不含任何密钥：

- `agents`：`AgentRegistry` 中全部已注册 Agent 的摘要（id、展示名、状态、职责说明、
  形态 `kind`、是否已工具化 `exposedAsTool`、子 Agent 数量）。注册表为空时退化为仅主 Agent。
- `tools`：已注册工具的名称、说明、风险等级与参数列表。
- `skills`：技能摘要（id、名称、说明、来源）；技能正文不通过该接口暴露，
  技能体系未启用时为空列表。
- `mcpServers`：MCP Server 配置摘要；仅暴露可执行程序名，不暴露启动参数。
- `models`：规划与直答两条链路的模型、provider（取 endpoint 主机名）与用途；
  不含 API Key 与完整端点。
- `limits`：运行预算（maxReplans / maxSteps / maxToolCalls / maxModelCalls）。

`/api/console/agents/{agentId}` 返回单个 Agent 详情，额外包含 `subAgents`（编排 Agent 的
子 Agent 标识）与 `tools`（YAML 配置化 Agent 声明的工具名）；未注册时返回 `404`。

`kind` 取值：`planner`（plan-execute-agent）、`supervisor`、`direct`（simple-qa-agent）、
`workflow`（含子 Agent 的编排 Agent）、`specialist`，以及 YAML 配置化 Agent 自带的
`specialist` / `sequential`。

### 查询会话

```http
GET /api/sessions?limit=50
GET /api/sessions/{sessionId}
```

列表按最后活跃时间倒序返回，`limit` 取值范围 1-200（默认 50，超出范围返回 `400`）。
单个会话不存在时返回 `404`。响应包含会话身份、状态键列表和事件增量合并后的结构化状态
（`lastObjective`、`lastStatus`、`turnCount` 及工具写入的 `stateDelta`）。

### 查询领域事件轨迹

```http
GET /api/events?sessionId=session-1&type=tool_call_completed
GET /api/events/{invocationId}
```

按会话查询时结果以 Invocation 分组，最近的 Invocation 排在最前；每组包含
`agentId`、起止时间、`eventCount`、终态类型 `terminalType`（`AGENT_COMPLETED` /
`AGENT_FAILED`，未收口时为空）和按时间排序的完整事件列表。`type` 参数可选，
大小写不敏感，不合法的名称按未过滤处理。按 Invocation 查询时无事件记录返回 `404`。

事件 JSON 包含 `eventId`、`sessionId`、`invocationId`、`agentId`、`timestamp`、
`type`、`message`、`data` 和 `actions`（`stateDelta` / `transferToAgent` /
`endInvocation` / `requireApproval`）。该接口读取 `AgentEventStore` 当前快照，
`memory` 持久化模式下进程重启后清空。

### 查询记忆快照

```http
GET /api/memories?sessionId=session-1&teamId=default-team&userId=default-user&agentId=plan-execute-agent&recentLimit=20
```

`sessionId` 必填，用于限定 L0 最近对话；`teamId`、`userId` 和 `agentId` 省略时使用默认值。
响应包含各层数量以及 `recentTurns`、`atomicMemories`、`scenarios` 和 `profile` 实际数据。
接口只读取当前快照，不等待正在异步生成的 L1-L3 记忆。由于响应可能包含用户画像和历史输入，
生产环境应在网关或安全层限制该接口的访问权限。

管理接口中的数量是当前作用域可见的存储总量；`[agent-memory]` 日志中的数量则是经过召回策略
筛选后，本次实际发送给规划模型的数量，两者可能不同。

## 技能与代码执行

技能是"完成某类任务的操作指南"，正文不常驻提示词：规划器从 `load_skill` 工具描述
看到技能摘要，需要时以 `skill_id` 换取完整指令。默认内置 `report-writing` 与
`code-review` 两个 classpath 技能；`agentos.skills.root` 指向的本地目录优先级更高，
可覆盖同 ID 内置技能：

```yaml
agentos:
  skills:
    enabled: true
    root: ".agentos/skills"                     # <root>/<技能名>/SKILL.md
    classpath-resources: "skills/report-writing/SKILL.md,skills/code-review/SKILL.md"
```

`execute_code` 工具执行 Python/Shell/Java/JavaScript/Go 代码片段，执行环境由
`agentos.tools.code-executor.mode` 决定：

- `local`（默认）：宿主机直跑，HIGH 风险需 HITL 审批。
- `auto`：Docker 守护进程可达且所需镜像均已在本地时走沙箱，否则回退本地进程；
  不会因为只检测到 Docker 守护进程就在首次任务中隐式拉取镜像。
- `docker`：强制一次性容器（`--network none`、内存/CPU 限额、源码只读挂载），LOW 风险免审批。

## 持久化

```yaml
agentos:
  persistence:
    mode: "${AGENTOS_PERSISTENCE_MODE:memory}"          # memory | sqlite
    sqlite-file: "${AGENTOS_PERSISTENCE_SQLITE_FILE:.agentos/runtime/runtime.sqlite}"
```

- `memory`：默认，零依赖启动，运行态保存在进程内。
- `sqlite`：领域事件、审批 Checkpoint、断点续跑状态与用量账本写入同一 SQLite 文件；
  进程重启（含 `kill -9`）后任务可恢复，等待审批的运行批准后继续执行，用量账本仍可查询。

## 多轮会话上下文

`SessionHistoryService` 在运行前从事件存储回放最近轮次（默认 5 轮，单条 400 字符截断），
同时注入简单 QA 直答与规划两条链路的 prompt。因此 "今天几号" 之后的 "那明天呢"、
"什么是 JVM" 之后的 "它和 JRE 的区别是什么" 可以正确解析指代。

```yaml
agentos:
  history:
    max-turns: 5
    max-message-chars: 400
```

## REST 鉴权

```yaml
agentos:
  security:
    api-key: "${AGENTOS_API_KEY:}"
```

配置非空后，全部 `/api/**` 请求必须携带 `X-API-Key` 请求头；留空关闭鉴权，便于本地开发。
上线 `run_command` 等高危工具前必须配置。

## 构建与启动

从项目根目录执行：

```bash
mvn test
mvn -pl agentos-server -am package
java -jar agentos-server/target/agentos-server-0.0.1-SNAPSHOT.jar
```

应用默认启用 `LlmAgentPlanner` 和 OpenAI-compatible `ModelClient`。模型参数统一配置在
`src/main/resources/application.yml` 中：

完整示例见 [`src/main/resources/application-example.yml`](src/main/resources/application-example.yml)，
该文件不会被 Spring 自动加载，可以复制为 `application.yml` 后使用。

```yaml
agentos:
  runtime:
    max-replan-count: 6
    max-step-count: 30
    max-tool-calls: 30
    max-model-calls: 10
    max-final-answer-chars: 100000
    max-final-draft-chars: 32000
    max-observation-chars: 4000
    max-observation-total-chars: 24000
  model:
    model: "your-model-id"
    api-key: "${AGENTOS_MODEL_API_KEY:}"
    endpoint: "https://api.minimaxi.com/v1/chat/completions"
    response-format: "NONE"
    reasoning-split: true
    connect-timeout: "10s"
    request-timeout: "600s"
    max-prompt-chars: 60000
```

本地可以直接修改 YAML 中的模型和端点。真实 API Key 不建议写入并提交到 Git，推荐只在 IDEA
Run Configuration 中设置：

```powershell
$env:AGENTOS_MODEL_API_KEY = "your-api-key"
java -jar agentos-server/target/agentos-server-0.0.1-SNAPSHOT.jar
```

所有 YAML 配置仍可被同名 Environment variables 覆盖。`API_KEY` 对无需鉴权的本地
OpenAI-compatible 服务可以留空。常用配置映射如下：

| 配置项 | 环境变量 | 默认值 |
| --- | --- | --- |
| `agentos.model.model` | `AGENTOS_MODEL_MODEL` | `MiniMax-M3` |
| `agentos.model.chat-model` | `AGENTOS_MODEL_CHAT_MODEL` | 空，复用主模型 |
| `agentos.model.endpoint` | `AGENTOS_MODEL_ENDPOINT` | `https://api.minimaxi.com/v1/chat/completions` |
| `agentos.model.api-key` | `AGENTOS_MODEL_API_KEY` | 空 |
| `agentos.model.response-format` | `AGENTOS_MODEL_RESPONSE_FORMAT` | `NONE`（MiniMax） |
| `agentos.model.reasoning-split` | `AGENTOS_MODEL_REASONING_SPLIT` | `true`（MiniMax-M3） |
| `agentos.model.request-timeout` | `AGENTOS_MODEL_REQUEST_TIMEOUT` | `600s` |
| `agentos.persistence.mode` | `AGENTOS_PERSISTENCE_MODE` | `memory` |
| `agentos.persistence.sqlite-file` | `AGENTOS_PERSISTENCE_SQLITE_FILE` | `.agentos/runtime/runtime.sqlite` |
| `agentos.security.api-key` | `AGENTOS_API_KEY` | 空，关闭鉴权 |
| `agentos.security.allow-host-processes` | `AGENTOS_ALLOW_HOST_PROCESSES` | `false` |
| `agentos.artifacts.root` | `AGENTOS_ARTIFACTS_ROOT` | `.agentos/artifacts` |
| `agentos.tools.file-access.root` | `AGENTOS_FILE_ACCESS_ROOT` | 服务进程当前目录 |
| `agentos.tools.run-command.enabled` | `AGENTOS_RUN_COMMAND_ENABLED` | `false` |
| `agentos.tools.run-command.work-dir` | `AGENTOS_RUN_COMMAND_WORK_DIR` | 服务进程当前目录 |
| `agentos.tools.run-command.timeout-seconds` | `AGENTOS_RUN_COMMAND_TIMEOUT_SECONDS` | `60` |
| `agentos.tools.run-command.max-output-chars` | `AGENTOS_RUN_COMMAND_MAX_OUTPUT_CHARS` | `20000` |
| `agentos.tools.web-fetch.timeout-seconds` | `AGENTOS_WEB_FETCH_TIMEOUT_SECONDS` | `20` |
| `agentos.tools.web-fetch.max-chars` | `AGENTOS_WEB_FETCH_MAX_CHARS` | `12000` |
| `agentos.tools.browser-search.endpoint` | `AGENTOS_BROWSER_SEARCH_ENDPOINT` | `http://localhost:8888/search`（SearXNG） |
| `agentos.tools.browser-search.timeout-seconds` | `AGENTOS_BROWSER_SEARCH_TIMEOUT_SECONDS` | `20` |
| `agentos.tools.code-executor.enabled` | `AGENTOS_CODE_EXECUTOR_ENABLED` | `false` |
| `agentos.tools.code-executor.mode` | `AGENTOS_CODE_EXECUTOR_MODE` | `local` |
| `agentos.tools.code-executor.timeout-seconds` | `AGENTOS_CODE_EXECUTOR_TIMEOUT_SECONDS` | `60` |
| `agentos.tools.code-executor.max-output-chars` | `AGENTOS_CODE_EXECUTOR_MAX_OUTPUT_CHARS` | `20000` |
| `agentos.skills.enabled` | `AGENTOS_SKILLS_ENABLED` | `true` |
| `agentos.skills.root` | `AGENTOS_SKILLS_ROOT` | `.agentos/skills` |
| `agentos.skills.classpath-resources` | — | 内置两个技能资源 |
| `agentos.history.max-turns` | — | `5` |
| `agentos.router.simple-qa.max-chars` | — | `64` |
| `agentos.router.short-circuit.max-chars` | — | `16` |
| `agentos.router.short-circuit.greeting-message` | — | `你好！有什么我可以帮你的吗？` |
| `agentos.router.short-circuit.acknowledgement-message` | — | `好的。` |
| `agentos.router.short-circuit.thanks-message` | — | `不客气！有需要随时告诉我。` |
| `agentos.router.short-circuit.farewell-message` | — | `晚安，祝你好梦。` |
| `agentos.router.supervisor.min-confidence` | `AGENTOS_ROUTER_SUPERVISOR_MIN_CONFIDENCE` | `0.75` |
| `agentos.runtime.max-concurrent-runs` | — | `128` |
| `agentos.runtime.stream.max-concurrent-runs` | — | `128` |
| `agentos.runtime.stream.queue-capacity` | — | `256` |
| `agentos.runtime.retention.max-runs` | — | `1000` |
| `agentos.runtime.retention.max-events-per-run` | — | `2000` |
| `agentos.runtime.retention.max-invocations` | — | `10000` |
| `agentos.runtime.retention.max-sessions` | — | `5000` |
| `agentos.runtime.retention.max-events-per-invocation` | — | `200` |
| `agentos.runtime.retention.max-events-per-session` | — | `2000` |

默认适配器根据当前注册工具动态生成计划 JSON Schema。若兼容服务不支持 `json_schema`，可将
`AGENTOS_MODEL_RESPONSE_FORMAT` 改为 `JSON_OBJECT`；连 `response_format` 参数也不支持时改为 `NONE`。
业务应用仍可自行声明 `ModelClient` Bean，Spring 会让自定义实现覆盖默认适配器。

模型可以替换为任意真正兼容 OpenAI Chat Completions 请求/响应格式的服务。`endpoint` 必须是
最终的 completions API 地址，不能填写会返回 301 的控制台或 API 根地址。不同兼容服务对
`response_format` 和额外推理字段的支持不一致，应据服务文档调整这两个配置。

运行预算是单次 HTTP 运行的累计限制：步骤按实际处理数计数，工具重试计入 `max-tool-calls`，
初始规划与每次 Decision 模型调用都计入 `max-model-calls`。只有 Decision 返回 REPLAN 时才计入
`max-replan-count`；返回 COMPLETE 不再产生无意义的 replan 计数。

## 意图路由

请求先经分层作用域和能力分类，避免一次错误路由扩散到整条执行链：

1. 问候、感谢、确认和告别白名单（≤16 字符）按类别返回自然应答，不调用模型；
   `yes/no` 等上下文回答在存在会话历史时不会直接短路。
2. 当前 AgentOS 的工具、Agent、Skill、MCP、模型和限制由 `system-catalog-agent`
   直接读取运行时注册表，不调用模型和网络工具；同名外部产品有歧义时先澄清。
3. 简单 QA（≤64 字符且不含任务信号词）派发到 `simple-qa-agent` 单次直答，
   不携带工具定义、不进入规划循环。
4. 其余请求由 Supervisor 输出结构化的作用域、所需能力、目标 Agent 和置信度；
   专家执行前必须通过能力与作用域接单校验，拒单或非法决策安全回退 PlanExecuteAgent。

Supervisor 低于 `agentos.router.supervisor.min-confidence` 时不会直接派发：作用域歧义返回
澄清问题，其余请求回退 PlanExecuteAgent。只有执行前拒单会自动改派；工具调用开始后的失败按真实
执行失败处理，避免重复写文件、运行命令或触发其他副作用。

路由分级阈值经 `agentos.router.*` 配置，规则细节见 [`agentos-agent`](../agentos-agent/ReadMe.md)。

## 执行日志

服务端默认以 `INFO` 级别记录一次 Agent 运行的关键阶段，并使用 `sessionId` 和 `planId` 串联：

| 日志标签 | 内容 |
| --- | --- |
| `[agent-run]` | 运行开始、最终成功、最终失败和总耗时 |
| `[model-call]` | 模型请求开始、HTTP 状态、模型生成步骤数和耗时 |
| `[chat-call]` / `[chat-stream]` | 简单 QA 直答与流式调用 |
| `[agent-plan]` | 计划目标、步骤总数以及每个规划步骤 |
| `[agent-observation]` | 工具结果的有界摘要、状态和失败类型 |
| `[agent-decision]` | Observation 是否充分以及 COMPLETE / REPLAN 结果 |
| `[agent-replan]` | 仅在 Decision 确实要求后续计划时记录次数 |
| `[agent-step]` | 步骤开始、工具名称、执行结果、审批状态和耗时 |
| `[agent-memory]` | 规划前召回的 L0-L3 数量、降级状态和耗时，以及成功运行后的记忆写入异常 |
| `[auth]` | API Key 鉴权启用/关闭状态 |

示例：

```text
[agent-run] started sessionId=session-1 taskId= agentId=plan-execute-agent iteration=1
[agent-memory] recalled sessionId=session-1 teamId=default-team userId=default-user agentId=plan-execute-agent taskId= l0Count=0 l1Count=3 l2Count=1 l3Count=1 degraded=false durationMs=4
[model-call] finished sessionId=session-1 model=MiniMax-M3 status=200 stepCount=1 durationMs=820
[agent-plan] created sessionId=session-1 planId=... type=DISCOVERY origin=INITIAL outcome=CONTINUE stepCount=1 ...
[agent-step] started sessionId=session-1 planId=... position=1/1 stepId=step1 tool=directory_list optional=false ...
[agent-step] finished sessionId=session-1 planId=... stepId=step1 status=COMPLETED attempts=1 ...
[agent-observation] sessionId=session-1 planId=... stepId=step1 tool=directory_list status=COMPLETED summary=...
[agent-decision] finished sessionId=session-1 previousPlanId=... outcome=COMPLETE nextPlanId=... modelCalls=2 replans=0
[agent-plan] created sessionId=session-1 planId=... type=EXECUTION origin=REPLANNED outcome=COMPLETE stepCount=0 ...
[agent-run] finished sessionId=session-1 planId=... status=COMPLETED modelCalls=2 replans=1 steps=1 toolCalls=1 finalResult=...
```

步骤结果和最终结果会转换为单行并最多记录 1000 个字符；工具调用参数不会写入 `INFO` 日志。
发给 Planner 的 Observation 和完整 user prompt 还分别受运行时摘要预算与
`agentos.model.max-prompt-chars` 限制。

## 扩展建议

- 按需实现自定义 `ModelClient`，覆盖默认的 OpenAI-compatible 适配器。
- 注册新的 `AgentTool` Bean，工具会被自动加入注册表。
- 用真实审批渠道替换默认拒绝型 `ApprovalService`。
- 实现 `AgentPlugin` 接入自定义记账、审计或追踪插件，容器会自动收编。
- 多实例部署时将后台 Run、事件和记忆迁移到共享持久化基础设施。

该模块是唯一需要感知 Spring 的模块，领域逻辑应优先保留在其他模块中。
