# AgentOS × Google ADK Java 对照研究路线

> 参考项目：<https://github.com/google/adk-java>
>
> 研究目标：以 Google ADK Java 作为成熟参考实现，以 AgentOS 作为实验、验证和工程化实践平台，系统掌握 Agent 运行时的设计与实现。

## 1. 研究定位

本研究不是把 AgentOS 改造成 ADK Java 的复制品，也不以类名、API 或模块结构完全一致为目标。

ADK Java 用于回答“成熟 Agent SDK 如何处理通用问题”；AgentOS 用于回答“这些设计在 Java 21、Spring Boot、虚拟线程、显式 Planner/Executor 和企业级审批场景中应该如何落地”。

研究过程中重点对齐以下语义，而不是具体语法：

- Agent 生命周期与执行边界；
- 模型、工具和工作流的循环机制；
- Event、Session、State 与 Checkpoint 的职责；
- 流式输出、取消、暂停和恢复；
- 工具权限、风险控制与人工审批；
- Trace、Eval、Token 用量和故障诊断；
- MCP、Agent-as-Tool 与远程 Agent 协作。

AgentOS 已经形成独立的架构方向，以下能力应作为自身特点保留并持续验证：

- Java 21 Record、虚拟线程和纯 Java 领域核心；
- 显式 `Planner -> Validator -> Executor -> Observation -> Replan` 链路；
- L0-L3 分层记忆与混合召回；
- 工具内容级风险策略、HITL、Checkpoint 和断点续跑；
- 后台运行、SSE 补播、运行控制台和 SQLite 单机持久化。

## 2. 核心对象映射

| 研究主题 | ADK Java | AgentOS | 主要问题 |
| --- | --- | --- | --- |
| 运行入口 | `Runner`、`InMemoryRunner` | `AgentRunner`、`AgentRunCoordinator` | 一次运行如何创建、串行化、结束和清理 |
| 调用上下文 | `InvocationContext`、`RunConfig` | `InvocationContext`、`AgentExecutionLimits` | 身份、预算、取消、事件、产物如何传递 |
| Agent 抽象 | `BaseAgent`、`LlmAgent` | `AgentLoop`、`Agent`、`workflow.BaseAgent` | Agent 的最小职责和扩展边界是什么 |
| Agent 循环 | `BaseLlmFlow`、`SingleFlow`、`AutoFlow` | `PlanExecuteAgent`、`LlmAgentPlanner`、`LlmFlow` | 模型调用、工具调用和停止判断如何闭环 |
| 工具系统 | `BaseTool`、`FunctionTool`、`ToolContext` | `AgentTool`、`ToolDispatcher`、`ToolContext` | Schema、校验、执行、错误、确认如何统一 |
| 事件模型 | `Event`、`EventActions` | `AgentRunEvent`、`EventActions`、`AgentEventPublisher` | 事件是否可持久化、重放和恢复状态 |
| 会话状态 | `Session`、`BaseSessionService` | `Session`、`SessionService`、`SessionState` | 历史、状态和运行事件的边界是什么 |
| 工作流 | `SequentialAgent`、`ParallelAgent`、`LoopAgent` | 同名 Workflow Agents | 串行、并行、循环的状态和失败语义 |
| Agent 工具化 | `AgentTool`、Agent transfer | `AgentToolAdapter`、`SupervisorAgent` | 子 Agent 由谁控制，最终答案由谁负责 |
| 暂停恢复 | Resumability、long-running tool | `CheckpointStore`、`ContinuationStore`、HITL | 长任务和人工审批怎样安全恢复 |
| 可观测性 | Callback、Plugin、Tracing | `AgentPluginManager`、`TraceRecorder`、Usage | 如何定位一次运行的行为和成本 |
| 评估 | Agent evaluation | `ToolTrajectoryEvaluator`、`EvaluationService` | 如何用数据验证改动没有造成退化 |
| 外部协议 | MCP、A2A | MCP 注册、后续 A2A 研究 | 本地工具和远程 Agent 如何标准化接入 |

## 3. 对照研究方法

每个主题执行同一套闭环：

1. **提出问题**：明确本轮要解释的运行时问题，避免泛读源码。
2. **运行最小示例**：先观察 ADK Java 的外部行为和事件轨迹。
3. **阅读 ADK 实现**：沿入口、上下文、核心循环、事件和测试向下追踪。
4. **阅读 AgentOS 实现**：找到对应链路，绘制时序或状态变化。
5. **记录差异**：区分语法差异、技术栈差异和真正的语义差异。
6. **用测试验证**：先补 characterization test，再进行实现调整。
7. **形成决策**：对 ADK 设计选择“保留现状、借鉴、实验或暂不采用”。
8. **小步落地**：每次只处理一个语义问题，避免大范围重构。

每轮研究至少留下三类证据：

- 一份对照笔记或架构决策记录；
- 一个能够复现差异的测试；
- 一项可测量结论，例如正确率、事件完整性、恢复成功率、延迟或 Token 成本。

## 4. 分阶段路线

### 阶段一：最小 Agent 与工具调用

**ADK 阅读范围**

- `tutorials/city-time-weather`；
- `LlmAgent`；
- `BaseTool`、`FunctionTool`、`ToolContext`；
- 对应单元测试。

**AgentOS 对照范围**

- `SimpleQaAgent`、`PlanExecuteAgent`；
- `AgentTool`、`ToolDefinition`、`ToolDispatcher`、`ToolResult`；
- `OpenAiCompatibleModelClient`。

**研究问题**

- 工具描述怎样转成模型可理解的 JSON Schema？
- 参数缺失、类型错误、工具异常和业务失败如何区分？
- 工具结果如何进入下一次模型调用？
- 最大模型调用次数和工具调用次数在哪里控制？

**阶段产物**

- 一个时间/天气类最小 Agent；
- 工具调用成功、参数错误、执行失败、超时四类测试；
- AgentOS 工具错误模型的对照结论。

### 阶段二：Runner、Context、Event 与 Session

**ADK 阅读范围**

- `Runner`、`InvocationContext`；
- `Event`、`EventActions`；
- `Session`、`BaseSessionService`、`InMemorySessionService`；
- Runner 和 Session 测试。

**AgentOS 对照范围**

- `AgentRunner`、`InvocationContext`；
- `AgentRunEvent`、`EventActions`、`AgentEventStore`；
- `Session`、`SessionService`、`SessionState`；
- SSE 事件补播与 SQLite 实现。

**研究问题**

- 用户输入是在运行前还是运行后写入 Session？
- partial event 是否持久化，最终事件如何聚合？
- 状态更新是快照覆盖还是 delta 合并？
- 同一 Session 的并发请求如何拒绝、排队或串行执行？
- 进程崩溃后，哪些信息足以重建运行状态？

**阶段产物**

- 一张端到端事件时序图；
- Session 并发、事件顺序、SSE 重连和状态重放测试；
- Event 与 Session 职责边界的架构决策记录。

### 阶段三：Agent Loop 与请求处理流水线

**ADK 阅读范围**

- `BaseLlmFlow.runOneStep()` 和 `run()`；
- `RequestProcessor`、`ResponseProcessor`；
- `SingleFlow`、`AutoFlow`；
- `Functions` 和 `BaseLlmFlowTest`。

**AgentOS 对照范围**

- `LlmFlow` 及其 processors；
- `LlmAgentPlanner`、`PlanValidator`、`PlanExecutor`；
- `PlanExecuteAgent` 的 Observation、Decision 和 Replan 循环。

**研究问题**

- 通用 ReAct 循环与显式 Planner/Executor 各适合什么任务？
- Prompt、历史、记忆和工具定义应以什么顺序注入？
- 停止条件是否覆盖最终回答、预算耗尽、人工暂停、取消和异常？
- 每一步完成后，事件是否已经持久化再进入下一步？

**阶段产物**

- ADK 循环与 AgentOS 规划循环的状态机对照；
- Direct、ReAct、Plan 三种策略的统一评测用例；
- 对 AgentOS 执行策略边界的明确说明。

### 阶段四：Workflow 与多 Agent

**ADK 阅读范围**

- `SequentialAgent`；
- `ParallelAgent`；
- `LoopAgent`；
- Agent transfer 与 Agent-as-Tool。

**AgentOS 对照范围**

- `workflow.SequentialAgent`；
- `workflow.ParallelAgent`；
- `workflow.LoopAgent`；
- `AgentToolAdapter`、`SupervisorAgent` 和 specialist agents。

**研究问题**

- 子 Agent 是否共享可变状态？
- 并行分支的事件、状态 delta 和错误如何合并？
- 循环由固定次数、事件动作还是模型决定退出？
- Handoff 和 Agent-as-Tool 的控制权差异是什么？
- 父子 Agent 的预算、Trace 和最终回答怎样归属？

**阶段产物**

- “需求分析 -> 实施 -> 审查”串行工作流；
- 搜索和代码分析并行工作流；
- 分支失败、循环退出、预算累计和状态合并测试。

### 阶段五：HITL、Checkpoint 与恢复

**ADK 阅读范围**

- Tool confirmation；
- long-running tool；
- resumable invocation；
- pending call 的暂停与恢复测试。

**AgentOS 对照范围**

- `ApprovalToolInterceptor` 和风险策略；
- `CheckpointStore`、`ContinuationStore`；
- SQLite Checkpoint/Continuation 持久化；
- 审批接口和后台运行恢复。

**研究问题**

- 暂停时必须持久化哪些数据？
- 批准、拒绝、超时、重复提交是否幂等？
- 恢复后怎样避免重复调用模型或重复执行工具？
- 代码升级后旧 Checkpoint 是否还能读取？

**阶段产物**

- 文件写入或 Shell 命令审批案例；
- 进程重启后批准、拒绝和恢复测试；
- Checkpoint 版本兼容和幂等策略说明。

### 阶段六：可观测性、评估与协议扩展

**ADK 阅读范围**

- callbacks、plugins、telemetry；
- agent evaluation；
- MCP toolset；
- A2A 集成。

**AgentOS 对照范围**

- `AgentPluginManager`、`TraceRecorder`、Usage；
- `ToolTrajectoryEvaluator`、`EvaluationService`；
- MCP 注册与工具生命周期；
- A2A 可行性设计。

**研究问题**

- 一次运行能否关联 model call、tool call、approval 和 artifact？
- Eval 是否覆盖最终答案和工具轨迹，而不只检查文本？
- Prompt、模型或工具变更后能否自动发现退化？
- MCP 工具与本地工具是否具有相同的权限、错误和观测语义？
- 什么时候需要 A2A，什么时候本地 Agent-as-Tool 已经足够？

**阶段产物**

- 代表性 Eval 数据集和回归基线；
- Trace、Token、延迟、错误率统一视图；
- MCP 安全边界清单；
- A2A 架构决策记录，不以接入协议本身作为目标。

## 5. 建议节奏

以六个阶段为一个完整周期，每个阶段建议投入 3-5 个有效学习日：

| 日程 | 工作内容 |
| --- | --- |
| 第 1 天 | 运行 ADK 示例，提出问题，确定跟踪入口 |
| 第 2 天 | 阅读 ADK 核心实现和测试 |
| 第 3 天 | 阅读 AgentOS 对应链路并完成对照笔记 |
| 第 4 天 | 为 AgentOS 增加验证测试或最小实验 |
| 第 5 天 | 小步改进、跑回归测试、记录设计决策 |

时间紧张时可以压缩实现范围，但不能省略“测试验证”和“记录决策”。

## 6. 研究记录模板

每个主题可在 `docs/adk-comparison/` 下建立独立文档，并使用以下模板：

```markdown
# 主题名称

## 要解决的问题

## ADK Java 的执行链路

## AgentOS 的执行链路

## 关键差异

| 维度 | ADK Java | AgentOS | 影响 |
| --- | --- | --- | --- |

## 验证测试

## 性能、成本和可靠性数据

## 决策

- [ ] 保留 AgentOS 现状
- [ ] 借鉴 ADK 设计
- [ ] 建立实验分支
- [ ] 暂不采用

## 后续行动
```

建议的主题文件：

```text
docs/adk-comparison/
├── 01-runner-context.md
├── 02-agent-loop.md
├── 03-event-session.md
├── 04-tools-hitl.md
├── 05-workflow-agents.md
├── 06-resumability.md
└── 07-observability-evals-protocols.md
```

## 7. 完成标准

完成本路线不以“读完多少源码”或“复制多少 ADK 功能”衡量，而以以下结果衡量：

- 能够从用户输入完整解释到最终事件持久化的执行链路；
- 能够独立实现最小 Agent Loop、Tool Calling 和 Session/Event 模型；
- 能够说明 ADK 通用循环与 AgentOS Planner/Executor 的适用边界；
- 能够用测试证明取消、并发、审批、恢复和状态合并语义；
- 能够通过 Eval 数据判断一次 Prompt、模型、工具或架构改动是否有效；
- 能够为每项借鉴留下明确的设计理由，而不是因为“ADK 也是这样做”；
- AgentOS 始终保持可构建、可测试、可观测和可回滚。

## 8. 当前优先级

第一优先级不是增加新的 Agent、工具或协议，而是完成以下三项对照研究：

1. `Runner + InvocationContext + Event + Session` 的完整运行和持久化语义；
2. `BaseLlmFlow` 与 AgentOS `Planner/Executor` 两类 Agent Loop 的边界；
3. Tool Calling、HITL、Checkpoint 和恢复过程的幂等性。

这三项决定 AgentOS 的运行时基础是否可靠。完成后，再继续 Workflow、Memory、Eval、MCP 和 A2A。
