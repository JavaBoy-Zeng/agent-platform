# AgentOS 已实现功能总结

> 文档基线：2026-08-11 当前工作区代码
>
> 目标读者：项目成员、新加入的研发人员、架构师和运维人员
>
> 说明：本文以实际代码和各模块 ReadMe 为准，反映 AgentOS 当前已实现的功能范围，不包含尚未完成的演进项。

## 1. 项目整体定位

AgentOS 是一个基于 Java 21、Maven 多模块与 Spring Boot 的模块化 Agent 运行时骨架，围绕"记忆召回 → 模型规划 → 计划校验 → 工具执行 → 观察汇总 → 完成判断或重规划 → 结果记忆化"的链路提供完整实现，并通过 REST 与 SSE 对外暴露能力。

- **领域核心与框架适配分离**：`agentos-kernel`、`agentos-planner`、`agentos-tool`、`agentos-memory`、`agentos-hitl`、`agentos-agent` 保持纯 Java 模块；仅 `agentos-server` 感知 Spring。
- **前端独立部署**：`agentos-console` 是 Vue 3 独立项目，通过 HTTP `/api` 与后端通信，不进入 Maven Reactor。
- **核心特点**：边界明确、可替换端口多；支持单计划/累计预算、确定性摘要、结构化失败分类与人在回路风险门禁。
- **技术栈**：Java 21（Record、虚拟线程）、Maven 多模块、Spring Boot 4.1.0、JDK `HttpClient`、Jackson、Apache POI 5.4.1、PDFBox 3.0.4；前端 Vue 3.5 + Vite 7 + marked + DOMPurify。

## 2. 模块级已实现功能

### 2.1 `agentos-kernel`（运行内核）

- 请求与上下文模型：`AgentRequest`（sessionId、目标、扩展属性）与 `AgentContext`（teamId、userId、agentId、taskId）。
- 执行预算：`AgentExecutionLimits`（重规划、步骤、工具、模型调用累计预算）。
- 状态快照：`AgentState`（不可变，包含状态、迭代次数、输出、错误和更新时间）。
- 执行协议：`AgentLoop`（函数式）+ `AgentRuntime`（统一运行入口，按会话保存状态并串行化更新）。
- 状态流转：`READY → RUNNING → COMPLETED / FAILED`，`WAITING_APPROVAL` 与 `CANCELLED` 已在模型中预留。
- 不依赖其他 AgentOS 模块，可在命令行、测试或其他容器中独立使用。

### 2.2 `agentos-planner`（规划器）

- 规划接口：`AgentPlanner` / `LlmAgentPlanner`，提供 `createPlan`、兼容的 `replan` 和基于 Observation 的 `decide`。
- 计划模型：`AgentPlan`、`PlanType`（`DISCOVERY` / `EXECUTION`）、`PlanOrigin`（`INITIAL` / `REPLANNED`）、`PlanOutcome`（`CONTINUE` / `COMPLETE`）、`PlanStep`、`Observation`、`ObservationSummarizer`。
- 迭代流程：Discovery → Observation → Decision，`COMPLETE` 走 Finalizer，`REPLAN` 走下一份计划。
- 模型 DTO：独立的 `ModelPlan`，仅暴露 `type / outcome / objective / steps / finalAnswer`，计划 id 与 origin 由 Runtime 注入。
- 校验与预算：`PlanValidator` 默认每份计划最多 10 步，校验工具存在性、必填参数、参数类型和未知参数；`DISCOVERY` 仅允许低风险工具。
- 执行器：`PlanExecutor` 顺序执行步骤并返回 COMPLETE / REPLAN / TERMINATED。
- 失败分类：`FailureClassifier` 把结构化工具失败分类为 `RETRY / SKIP / REPLAN / ABORT`。
- 快照：`PlanExecutionSnapshot` 保留累计 `stepResults`、有界 `observations`、当前步骤、最后结果和原因。
- 决策：`AgentDecision` 明确区分 `COMPLETE` 与 `REPLAN`，仅后者消耗重规划预算。

### 2.3 `agentos-tool`（工具协议与内置工具）

- 分包边界：`api` 提供工具协议，`runtime` 提供注册与统一调度，`builtin` 提供默认文件、Git、回显和天气工具；ArchUnit 自动守护依赖方向。
- 核心类型：`AgentTool`、`ToolParameter` / `ToolDefinition`、`ToolCall`、`ToolResult`（含 `ToolFailureType`）、`ToolRegistry`、`ToolDispatcher`。
- 文件访问：`FileAccessPolicy` 抽象；当前服务端装配防路径遍历和符号链接逃逸的 `RootedFileAccessPolicy`。
- 分页读取：`PagedFileReader` / `PagedReadResult` 支持按物理页和页内偏移读取（含 PDF），并显式返回 `hasMore` / `nextPage` / `nextOffset` / `truncated`。
- 内置工具：
  - `directory_list`：有界列出目录，深度默认 2 / 最大 5，条目默认 200 / 最大 500，不跟随符号链接。
  - `file_search`：`NAME` glob 或 `CONTENT` 字面量搜索，深度默认 8 / 最大 12，结果默认 100 / 最大 500，最多扫描 20,000 文件；内容搜索跳过符号链接、二进制、不可读和 >1 MiB 文件并返回带行号匹配。
  - `file_read`：PDF 按物理页与页内偏移分页，单页正文最多 3000 字符；首次可省略 `page`/`offset`。
  - `echo`：仅用于调用链测试。
  - `weather`：调用外部天气接口（默认风险等级 MEDIUM）。
- 结构化失败类型：`INVALID_ARGUMENT / NOT_FOUND / TRANSIENT / ACCESS_DENIED / PERMISSION_DENIED / SECURITY_DENIED / TOOL_INTERNAL_ERROR / UNKNOWN`。
- `ToolDispatcher` 统一完成工具解析、拦截器生命周期、异常转换、并行安全降级和事件发布。

### 2.4 `agentos-memory`（L0–L3 记忆）

- 统一入口：`MemoryService`（`recall` / `capture` / 手工事实写入 / 分层数据查询）。
- 数据模型：
  - L0：`CompletedTurn`（请求目标、Agent 最终输出、成功工具结果的有界摘要、作用域、完成时间）。
  - L1：`AtomicMemory` + `MemoryType`（事实 / 偏好 / 约束 / 决策 / 事件 / 经验 / 画像）。
  - L2：场景记忆聚合。
  - L3：用户 / Agent 画像归纳。
- 流水线：`MemoryPipeline` 异步加工 L1–L3，含规范化精确去重、Jaccard 相似合并、版本递增。
- 持久化：支持 JVM 内存、版本化二进制文件和带迁移/索引的 JDBC/SQLite。
- 召回：`HybridMemoryRetriever`（BM25 + 可替换 Embedding + RRF），`MemoryContextFormatter` 组装受限上下文。
- 抽取：`MemoryModel` 抽象接口；默认使用规则模型，也可接入 OpenAI-compatible Chat/Embedding。
- 集成：已接入 `LlmAgentPlanner` 的 `recall()` 与 `MainAgent` 的 `capture()`；记忆写入失败采用 fail-open。
- 范围限制：成功工具观察写入前按单条 4,000 字符 / 总计 24,000 字符裁剪，优先保留最新结果；
  L0 不是完整原始日志，不能用于严格事件重放。

### 2.5 `agentos-hitl`（人在回路）

- 风险策略：`RiskPolicy` 按工具声明的风险等级（`LOW / MEDIUM / HIGH`）与阈值判断是否需要审批。
- 审批服务：`ApprovalService` + `ApprovalHandler`，把待审批调用转为 `ApprovalRequest` 并获取布尔结果。
- 默认安全：`agentos-server` 把阈值设为 `MEDIUM`，并使用默认拒绝处理器；低风险工具可直接执行，中高风险在接入真实审批渠道前保持阻断。
- 扩展点：可替换为真实审批渠道（管理后台、企业 IM、工作流系统）；同步接口适合当前骨架。

### 2.6 `agentos-agent`（业务编排）

- 主 Agent：`MainAgent` 在运行预算内循环执行"规划 → 工具 → 重新规划"。
- 重规划触发：`DISCOVERY_COMPLETED`、`RECOVERABLE_FAILURE`、`INVALID_ASSUMPTION`、`EXECUTION_COMPLETED`。
- 终态控制：仅 `EXECUTION / COMPLETE` 进入 `AgentFinalizer`；生产装配使用流式 Finalizer，额外消耗一次受预算约束的文本模型调用，并把可见 SSE 增量直接输出。Finalizer 是 Runtime 内部控制动作，不出现在工具注册表。
- 记忆写入：只有最终成功的运行会写入 `CompletedTurn`；记忆存储失败不会把成功运行改成失败。

### 2.7 `agentos-server`（Spring Boot 装配与对外 API）

- 入口与配置：`AgentOsApplication`（`@SpringBootApplication`）、`AgentOsConfiguration`、`LlmPlannerConfiguration`、`application.yml`。
- REST API：
  - `POST /api/agents/runs`：创建一次同步 Agent 运行（`agentId` / `sessionId` 可省略）。
  - `GET /api/agents/{sessionId}/state`：查询会话状态（不存在返回 404）。
  - `POST /api/agents/runs/stream`：SSE 流式运行，依次发送阶段事件、模型 `output_delta`、`run_completed` 和最终 `state`；模型增量不做定长二次切片。
  - `POST /api/agent-runs`：创建与浏览器连接解耦的后台运行，返回可持久化到前端的 `runId`。
  - `GET /api/agent-runs/{runId}`：查询后台运行快照。
  - `GET /api/agent-runs/{runId}/events?after={sequence}`：补播游标后的事件并继续实时订阅。
  - `POST /api/agent-runs/{runId}/cancel`：显式取消后台运行。
  - `GET /api/memories`：按作用域查询 L0–L3 记忆快照（只读），响应包含 `recentTurns`、`atomicMemories`、`scenarios`、`profile`。
- 异常处理：`AgentExceptionHandler` 把非法参数异常转换为标准 HTTP Problem Detail（400）。
- 模型适配：默认 `OpenAiCompatibleModelClient`，模型参数通过 `application.yml` 或环境变量（`AGENTOS_MODEL_API_KEY` 等）覆盖。
- 集成测试：`AgentRuntimeIntegrationTest` 覆盖规划、工具、状态、记忆写入的完整链路。
- 日志：步骤结果和最终结果以单行最多 1000 字符记录；工具调用参数不会写入 INFO 日志；发给 Planner 的 Observation 与完整 user prompt 受运行时摘要预算与 `agentos.model.max-prompt-chars` 限制。
- 构建：可独立打包为可执行 JAR（`mvn -pl agentos-server -am package`）。

### 2.8 `agentos-console`（Vue 3 控制台）

- 技术栈：Vue 3 Composition API、JavaScript、Vite 7、原生 Fetch 与 CSS 响应式布局。
- 组件：`SystemHeader`、`SessionRail`、`CommandDeck`、`TranscriptPanel`、`TelemetryRail`。
- 组合式与服务：`useAgentConsole` 管理状态与流式事件消费；`agentApi` 封装 REST/SSE 调用。
- 功能：
  - 创建和切换 Agent 会话。
  - 向 `POST /api/agent-runs` 创建后台任务，再按 SSE 游标补播并持续订阅。
  - 页面刷新后使用持久化的 `runId` 和最后事件序号恢复运行视图。
  - 实时展示 Plan、Tool、Observation、Decision、最终输出和失败信息。
  - 可视化 MainAgent → Planner → Tool → Observation → Decision 管线。
  - 服务端分页提供会话索引；`localStorage` 仅缓存最近 20 个会话用于快速恢复和断网兜底。
  - 本地缓存缺失时，从服务端领域事件恢复完整的用户/助手对话轮次。
  - 顶部提供中英文全局切换，持久化语言偏好并同步无障碍语言属性与日期格式。
  - 展示当前 HITL 风险门禁策略。
- Markdown 渲染：使用 `marked` + `DOMPurify` 安全渲染 Agent 输出。
- 开发与构建：`npm install` + `npm run dev`（Vite 把 `/api` 代理到 `http://localhost:8080`）；`npm run build` 产出 `dist`。

## 3. 默认运行时链路

```text
User → agentos-console / REST 调用方
        │  HTTP /api
        ▼
agentos-server (Spring Boot)
        │
        ▼
MainAgent
        ├── AgentPlanner.createPlan
        │       ├── MemoryService.recall (L0 最近 + L1/L2/L3 混合召回)
        │       ├── ModelClient (OpenAI-compatible)
        │       └── PlanValidator 校验
        ├── PlanExecutor.execute
        │       ├── ToolDispatcher + ToolRegistry
        │       ├── ApprovalToolInterceptor / RiskPolicy / ApprovalService
        │       └── 失败 → FailureClassifier → REPLAN / SKIP / ABORT
        ├── AgentPlanner.replan (受累计预算限制)
        └── AgentFinalizer.finishStreaming (仅 EXECUTION / COMPLETE)
                │
                └── MemoryService.capture (成功后异步 L1–L3 加工)
```

## 4. 运行时预算与限制

- 单次 Agent 运行：
  - 最大重规划次数：3。
  - 最大累计处理步骤数：30。
  - 最大工具调用数：30（含重试）。
  - 最大模型调用数：6（含初始与重规划）。
  - 最大 Observation 字符数：400（`max-observation-chars`）。
- Planner 每次发给模型的 `maxSteps`：取"单计划上限 10"与"剩余累计步骤预算"的较小值。
- `ObservationSummarizer`：单条默认 4,000 字符，累计 24,000 字符，优先保留最新结果。
- 记忆写入：单条工具观察 20,000 字符，总计 100,000 字符。
- 日志：步骤结果与最终结果单行最多 1000 字符；工具调用参数不会写入 INFO 日志。
- `Decision` 返回 `COMPLETE` 不计入 `maxReplanCount`。

## 5. 内置工具清单

| 工具名 | 风险 | 主要用途 | 关键边界 |
| --- | --- | --- | --- |
| `directory_list` | LOW | 有界列出目录 | 深度默认 2 / 最大 5；条目默认 200 / 最大 500；不跟随符号链接 |
| `file_search` | LOW | NAME glob / CONTENT 字面量搜索 | 深度默认 8 / 最大 12；结果默认 100 / 最大 500；最多扫描 20,000 文件 |
| `file_read` | LOW | 读取文件 / PDF | PDF 按物理页与页内偏移分页；单页正文最多 3000 字符；返回续读元数据 |
| `echo` | LOW | 调用链测试 | 不承担最终回答 |
| `weather` | MEDIUM | 查询外部天气接口 | 默认需要审批（阈值 MEDIUM） |

## 6. HTTP API 速查

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agents/runs` | 创建一次同步 Agent 运行 |
| GET | `/api/agents/{sessionId}/state` | 查询会话状态（不存在返回 404） |
| POST | `/api/agents/runs/stream` | 创建一次 SSE 流式 Agent 运行 |
| POST | `/api/agent-runs` | 创建连接解耦的后台 Agent 运行 |
| GET | `/api/agent-runs/{runId}` | 查询后台运行快照 |
| GET | `/api/agent-runs/{runId}/events?after=...` | 按游标补播并订阅运行事件 |
| POST | `/api/agent-runs/{runId}/cancel` | 显式取消后台运行 |
| GET | `/api/memories` | 按作用域查询 L0–L3 记忆快照（只读） |

## 7. 数据边界与存储

- 默认记忆持久化：本地二进制文件（`.agentos/memory/memory-state.bin`）。
- 测试模式：可切换为 JVM 内存模式（`InMemoryMemoryStore`）。
- 单实例持久化：可切换为 JDBC/SQLite；向量尚未持久化为独立索引。
- 控制台会话：仅保存在浏览器 `localStorage`，刷新页面仍存在，切换浏览器或清理站点数据会丢失；后端运行状态以 AgentOS API 返回结果为准。
- 后台运行：浏览器断开不取消任务，前端可以按事件序号补播；Run 和事件仍保存在服务进程内存中，
  服务重启后无法恢复。

## 8. 当前的"未实现 / 演进项"

为避免与已实现能力混淆，下述能力在当前代码库尚未提供或仅具备最小占位：

- Skill、Wiki、CodeGraph、Memory Asset 等资产模型：未实现。
- 持久化向量和向量索引：真实 Embedding 适配器已经提供，但当前仍在召回时逐条计算候选向量。
- 团队 / 用户 / Agent 角色治理、ACL、Memory HTTP Gateway、SDK、适配器、管理面板：未实现。
- 异步 HITL、持久化审批单、自动化恢复：未实现。
- 文件访问边界：`RootedFileAccessPolicy` 将内置文件工具限制在配置根目录内。
- API 鉴权：未内置网关鉴权，生产环境需在网关或安全层补齐。
- 多租户、任务调度、指标、审计：仍处于原型阶段。

## 9. 结论

AgentOS 当前已实现一个边界明确、可替换端口较多的 Agent 运行时骨架，覆盖：

- 状态机与运行内核（`agentos-kernel`）；
- 模型驱动的迭代规划与失败分类（`agentos-planner`）；
- 工具协议、分页读取与内置工具（`agentos-tool`）；
- L0–L3 分层记忆、混合检索与异步加工（`agentos-memory`）；
- 风险策略与人在回路（`agentos-hitl`）；
- 主 Agent 编排（`agentos-agent`）；
- Spring Boot 装配与 REST/SSE API（`agentos-server`）；
- Vue 3 可视化控制台（`agentos-console`）。

后续演进应优先完善密钥轮换、文件访问沙箱与 API 鉴权，再将运行状态、任务调度与记忆存储迁移到可持久化、可水平扩展的基础设施，并补齐异步 HITL、真实 Embedding、指标、审计与团队治理能力。
