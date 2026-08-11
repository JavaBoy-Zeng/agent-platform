# agentos-server

`agentos-server` 是 AgentOS 的 Spring Boot 启动与适配层，负责组装所有模块、提供 HTTP API，并生成可独立运行的应用程序。

## 主要职责

- 启动 Spring Boot Web 应用。
- 将规划器、工具、记忆、审批服务、主 Agent 和运行时装配为 Bean。
- 提供创建 Agent 运行和查询会话状态的 REST API。
- 提供 Planner、Tool、Observation、Decision 阶段的 SSE 流式运行 API。
- 提供按作用域查看 L0-L3 数据的只读记忆管理 API。
- 将请求参数错误转换为标准 HTTP Problem Detail 响应。
- 承载跨模块集成测试和可执行 JAR 打包。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `AgentOsApplication` | 标注 `@SpringBootApplication` 的应用入口。 |
| `AgentOsConfiguration` | 装配工具、记忆、审批、计划执行器、主 Agent 和运行时。 |
| `LlmPlannerConfiguration` | 装配 `LlmAgentPlanner` 和默认的 OpenAI-compatible `ModelClient`。 |
| `AgentController` | 暴露 Agent 运行和状态查询 API。 |
| `MemoryController` | 暴露 L0-L3 记忆快照只读查询 API。 |
| `AgentExceptionHandler` | 将非法参数异常转换为 HTTP 400。 |
| `AgentRuntimeIntegrationTest` | 验证规划、工具执行、状态更新和记忆写入的完整链路。 |

## 默认装配

```text
AgentRuntime
    └── MainAgent
        ├── AgentPlanner
        │   └── LlmAgentPlanner ──► ModelClient
        ├── PlanExecutor
        │   ├── FailureClassifier
        │   ├── ToolRegistry ──► directory_list / file_search / file_read / ...
        │   ├── ToolExecutor
        │   ├── RiskPolicy
        │   └── ApprovalService
        ├── AgentFinalizer
        └── MemoryService
            ├── ShortMemory
            └── LongMemory
```

## HTTP API

### 创建一次运行

```http
POST /api/agents/runs
Content-Type: application/json

{
  "agentId": "main-agent",
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

### 流式运行

```http
POST /api/agents/runs/stream
Content-Type: application/json
Accept: text/event-stream

{"sessionId":"session-1","input":"阅读当前项目并总结功能"}
```

接口依次发送 `run_started`、`plan_created`、`tool_started`、`tool_finished`、
`observation`、`decision`、可选的 `replan`，最后发送 `state`。流式内容是运行阶段事件；
Planner 的模型响应仍采用完整结构化 JSON 校验，最终回答随 `run_completed`/`state` 返回。

### 查询记忆快照

```http
GET /api/memories?sessionId=session-1&teamId=default-team&userId=default-user&agentId=main-agent&recentLimit=20
```

`sessionId` 必填，用于限定 L0 最近对话；`teamId`、`userId` 和 `agentId` 省略时使用默认值。
响应包含各层数量以及 `recentTurns`、`atomicMemories`、`scenarios` 和 `profile` 实际数据。
接口只读取当前快照，不等待正在异步生成的 L1-L3 记忆。由于响应可能包含用户画像和历史输入，
生产环境应在网关或安全层限制该接口的访问权限。

管理接口中的数量是当前作用域可见的存储总量；`[agent-memory]` 日志中的数量则是经过召回策略
筛选后，本次实际发送给规划模型的数量，两者可能不同。

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
    max-replan-count: 3
    max-step-count: 30
    max-tool-calls: 30
    max-model-calls: 6
    max-observation-chars: 4000
    max-observation-total-chars: 24000
  model:
    model: "your-model-id"
    api-key: "${AGENTOS_MODEL_API_KEY:}"
    endpoint: "https://api.minimaxi.com/v1/chat/completions"
    response-format: "NONE"
    reasoning-split: true
    connect-timeout: "10s"
    request-timeout: "60s"
    max-prompt-chars: 60000
```

本地可以直接修改 YAML 中的模型和端点。真实 API Key 不建议写入并提交到 Git，推荐只在 IDEA
Run Configuration 中设置：

```powershell
$env:AGENTOS_MODEL_API_KEY = "your-api-key"
java -jar agentos-server/target/agentos-server-0.0.1-SNAPSHOT.jar
```

所有 YAML 配置仍可被同名 Environment variables 覆盖。`API_KEY` 对无需鉴权的本地
OpenAI-compatible 服务可以留空。配置映射如下：

| 配置项 | 环境变量 | 默认值 |
| --- | --- | --- |
| `agentos.model.model` | `AGENTOS_MODEL_MODEL` | 无，必须显式指定 |
| `agentos.model.endpoint` | `AGENTOS_MODEL_ENDPOINT` | `https://api.minimaxi.com/v1/chat/completions` |
| `agentos.model.api-key` | `AGENTOS_MODEL_API_KEY` | 空 |
| `agentos.model.response-format` | `AGENTOS_MODEL_RESPONSE_FORMAT` | `NONE`（MiniMax） |
| `agentos.model.reasoning-split` | `AGENTOS_MODEL_REASONING_SPLIT` | `true`（MiniMax-M3） |
| `agentos.model.connect-timeout` | `AGENTOS_MODEL_CONNECT_TIMEOUT` | `10s` |
| `agentos.model.request-timeout` | `AGENTOS_MODEL_REQUEST_TIMEOUT` | `60s` |
| `agentos.model.max-prompt-chars` | `AGENTOS_MODEL_MAX_PROMPT_CHARS` | `60000` |

默认适配器根据当前注册工具动态生成计划 JSON Schema。若兼容服务不支持 `json_schema`，可将
`AGENTOS_MODEL_RESPONSE_FORMAT` 改为 `JSON_OBJECT`；连 `response_format` 参数也不支持时改为 `NONE`。
业务应用仍可自行声明 `ModelClient` Bean，Spring 会让自定义实现覆盖默认适配器。

模型可以替换为任意真正兼容 OpenAI Chat Completions 请求/响应格式的服务。`endpoint` 必须是
最终的 completions API 地址，不能填写会返回 301 的控制台或 API 根地址。不同兼容服务对
`response_format` 和额外推理字段的支持不一致，应据服务文档调整这两个配置。

运行预算是单次 HTTP 运行的累计限制：步骤按实际处理数计数，工具重试计入 `max-tool-calls`，
初始规划与每次 Decision 模型调用都计入 `max-model-calls`。只有 Decision 返回 REPLAN 时才计入
`max-replan-count`；返回 COMPLETE 不再产生无意义的 replan 计数。

## 执行日志

服务端默认以 `INFO` 级别记录一次 Agent 运行的关键阶段，并使用 `sessionId` 和 `planId` 串联：

| 日志标签 | 内容 |
| --- | --- |
| `[agent-run]` | 运行开始、最终成功、最终失败和总耗时 |
| `[model-call]` | 模型请求开始、HTTP 状态、模型生成步骤数和耗时 |
| `[agent-plan]` | 计划目标、步骤总数以及每个规划步骤 |
| `[agent-observation]` | 工具结果的有界摘要、状态和失败类型 |
| `[agent-decision]` | Observation 是否充分以及 COMPLETE / REPLAN 结果 |
| `[agent-replan]` | 仅在 Decision 确实要求后续计划时记录次数 |
| `[agent-step]` | 步骤开始、工具名称、执行结果、审批状态和耗时 |
| `[agent-memory]` | 规划前召回的 L0-L3 数量、降级状态和耗时，以及成功运行后的记忆写入异常 |

示例：

```text
[agent-run] started sessionId=session-1 taskId= agentId=main-agent iteration=1
[agent-memory] recalled sessionId=session-1 teamId=default-team userId=default-user agentId=main-agent taskId= l0Count=0 l1Count=3 l2Count=1 l3Count=1 degraded=false durationMs=4
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
- 用持久化存储替换内存型长期记忆。

该模块是唯一需要感知 Spring 的模块，领域逻辑应优先保留在其他模块中。
