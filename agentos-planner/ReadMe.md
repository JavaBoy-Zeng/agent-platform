# agentos-planner

`agentos-planner` 负责生成、校验和执行可迭代调整的 Agent 计划。计划不是一次性静态脚本：
探索获得新事实或遇到明确可恢复的工具失败后，Runtime 会携带累计执行快照再次调用规划器。

## 领域模型

| 类型 | 作用 |
| --- | --- |
| `AgentPlanner` | 提供 `createPlan`、兼容的 `replan`，以及基于 Observation 的 `decide`。 |
| `LlmAgentPlanner` | 召回记忆、调用 `ModelClient`、把模型 DTO 转成领域计划并校验。 |
| `ChatClient` | 简单问答、Specialist 总结和复杂任务最终回答使用的单轮/流式模型端口；与 `ModelClient` 平行，不携带工具定义。 |
| `flow.LlmFlow` | LLM 请求组装流水线：按序执行 `LlmRequestProcessor` 链后产出最终请求。 |
| `flow.LlmRequest / LlmMessage` | 面向厂商的消息模型（system 指令 + user/assistant 序列）。 |
| `flow.InstructionProcessor / HistoryProcessor` | 默认处理器：注入系统指令、把会话历史展开为消息序列。 |
| `AgentPlan` | Runtime 创建的计划，包含 `type`、`origin`、`outcome`、步骤或最终回答。 |
| `PlanType` | `DISCOVERY` 探索未知环境；`EXECUTION` 执行任务，也包含最终回答阶段。 |
| `PlanOrigin` | `INITIAL` 或 `REPLANNED`，描述计划如何产生，不进入模型输出 Schema。 |
| `PlanOutcome` | `CONTINUE` 表示执行工具步骤；`COMPLETE` 表示内部 Finalizer 可返回最终回答。 |
| `PlanStep` | 一个工具步骤；`optional` 是所有计划类型都可使用的通用语义。 |
| `Observation` | 原始工具结果的有界摘要，是 Planner 决策阶段使用的证据。 |
| `ObservationSummarizer` | 确定性摘要工具结果，不额外消耗模型调用。 |
| `PlanExecutionSnapshot` | 保留累计 `stepResults`，并携带有界 `observations`、当前步骤、最后结果和原因。 |
| `AgentDecision` | 明确区分 `COMPLETE` 和 `REPLAN`；仅后者消耗重规划预算。 |
| `PlanExecutor` | 顺序执行步骤，并返回完成、需要重规划或必须终止。 |
| `FailureClassifier` | 将结构化工具失败分类为 `RETRY / SKIP / REPLAN / ABORT`。 |

模型响应使用独立的 `ModelPlan` DTO，只包含：

- `type / outcome / objective`
- `steps`（`CONTINUE`）
- `finalAnswer`（`COMPLETE`）

计划 `id` 和 `origin` 由 Runtime 转换 DTO 时注入，不由模型生成。

## 迭代流程

```text
User → PlanExecuteAgent → Planner → Tool → Observation → Decision
                                                  ├── 信息充分 → COMPLETE → Finalizer
                                                  └── 信息不足 → REPLAN → 下一份计划
```

`final_answer` 不是 `AgentTool`。`COMPLETE` 计划必须是 `EXECUTION`、不得包含步骤，并且必须
包含非空 `finalAnswer`；`CONTINUE` 计划必须包含步骤且不得包含最终回答。

## 请求组装（LlmFlow）

发给模型的请求不是手工拼接的字符串，而是经 `flow` 包的处理器链组装：

```text
LlmFlow.run(LlmRequest, processors)
    ├── InstructionProcessor   注入 system 指令
    └── HistoryProcessor       展开会话历史为 user/assistant 消息
    ──► 最终 LlmRequest ──► ChatClient / ModelClient
```

处理器是纯函数式的 `LlmRequestProcessor`，按声明顺序对不可变请求做增量变换；
多轮上下文、prompt 预算裁剪等横切关注点以处理器接入，不侵入客户端实现。
简单 QA 直答与规划两条链路共用同一流水线。

## 失败策略

| 失败类型 | 必选步骤 | 可选步骤 |
| --- | --- | --- |
| `TRANSIENT` | 重试一次，仍失败则重规划 | 重试一次，仍失败则跳过 |
| `NOT_FOUND` | 以 `INVALID_ASSUMPTION` 重规划 | 跳过 |
| `INVALID_ARGUMENT` | 以 `RECOVERABLE_FAILURE` 重规划 | 跳过 |
| `ACCESS_DENIED / PERMISSION_DENIED / SECURITY_DENIED` | 终止 | 终止 |
| `TOOL_INTERNAL_ERROR / UNKNOWN` | 终止 | 终止 |

未知工具、计划校验失败、预算耗尽和人工审批拒绝也直接终止。内部错误不会交给规划器掩盖。

## 校验和预算

`PlanValidator` 默认限制每份计划最多 10 步，并验证工具存在性、必填参数、参数类型和未知参数。
`DISCOVERY` 只允许低风险工具。单次运行的累计预算由 `AgentExecutionLimits` 控制：

- 最大重规划次数：3
- 最大累计处理步骤数：30
- 最大工具调用数：30，包含重试
- 最大模型调用数：6，包含初始规划和重规划尝试

每次发给模型的 `maxSteps` 是单计划上限与剩余累计步骤预算的较小值。原始 `stepResults` 仍供
Runtime 审计，但 Planner 只接收 `ObservationSummarizer` 生成的摘要。默认单条 4,000 字符、
累计 24,000 字符，并优先保留最新结果。Decision 返回 COMPLETE 不计入 `maxReplanCount`。
