# agentos-agent

`agentos-agent` 是 AgentOS 的业务编排层，负责在运行预算内循环执行“规划 → 工具 → 重新规划”，
并只在得到明确最终回答后完成会话。模块同时提供可组合的 Workflow Agent 体系，
让纯编排 Agent 与 LLM 驱动的 Agent 共享同一抽象。

## 包结构

```text
com.github.agentos.agent
├── Agent / AgentExecutionResult      核心抽象（根包自包含）
├── registry/    AgentRegistry 与内存实现
├── finalize/    AgentFinalizer 收口器
├── loop/        MainAgent / SimpleQaAgent 等可执行实现
├── routing/     三级意图路由
├── strategy/    执行策略路由（DIRECT / REACT / PLAN）
└── workflow/    BaseAgent 体系与 Workflow Agents
```

依赖只能自上而下：根包不依赖任何子包；`registry`、`finalize` 是底层设施；
`loop` 是可执行实现的叶子包；`routing` 与 `strategy` 互不依赖。
规则由 `AgentModuleArchitectureTest` 守护。

## `MainAgent` 流程

```text
AgentRunner.run(AgentRequest, InvocationContext)
    └── MainAgent
        ├── AgentPlanner.createPlan(...)
        ├── PlanExecutor.execute(...)
        ├── AgentPlanner.replan(..., PlanExecutionSnapshot)
        │       └── 可重复，受累计预算限制
        ├── AgentFinalizer.finish(EXECUTION / COMPLETE)
        └── MemoryService.capture(CompletedTurn.success)
```

`AgentRequest` 保存 `sessionId`、用户目标和扩展属性；`InvocationContext` 整合团队、用户、
Agent、任务身份、会话状态与执行预算，是单次执行的完整运行世界。

`MainAgent` 在以下情况请求重规划：

- `DISCOVERY_COMPLETED`：探索计划成功并获得新环境信息。
- `RECOVERABLE_FAILURE`：工具失败但任务仍可恢复。
- `INVALID_ASSUMPTION`：例如原计划猜测的文件不存在。
- `EXECUTION_COMPLETED`：工具执行完成，需要模型综合真实结果。

只有 `EXECUTION / COMPLETE` 会进入 `AgentFinalizer`。Finalizer 是 Runner 内部控制动作，
不会出现在工具注册表中，也不会额外调用模型。

记忆采用 fail-open：只有最终成功的运行会写入 `CompletedTurn`；记忆存储失败不会把成功运行改成
失败。工具观察写入记忆前按单条 20,000 字符、总计 100,000 字符限制，并优先保留最新结果。

## Workflow Agents

`workflow` 包提供以 `BaseAgent` 为根的可组合 Agent 体系。`BaseAgent` 只回答
“我是谁、有哪些子 Agent、如何运行”，本身不绑定 LLM；子 Agent 执行时通过
`runChild` 派生以子 Agent 标识为中心的 `InvocationContext`（`withAgentId`），
会话、预算与事件发布器原样共享。

| 组件 | 语义 |
| --- | --- |
| `SequentialAgent` | 按声明顺序串行执行子 Agent；任一子 Agent 进入 FAILED / CANCELLED / WAITING 终态立即短路，全部成功后以最后一个非空输出完成 |
| `ParallelAgent` | 虚拟线程并行执行全部子 Agent，合并全部输出完成；任一失败则整体失败 |
| `LoopAgent` | 循环执行子 Agent 序列直到达到最大迭代次数或子 Agent 以 `escalate:` 前缀显式终止 |

`AgentToolAdapter` 将任意 `BaseAgent` 适配为 `AgentTool`：调用方通过 `objective` 参数
下发目标（可选 `sessionId` 指定会话），适配器将其转换为 `AgentRequest` 执行被包装的
Agent，并把 COMPLETED 状态映射为工具成功、其余状态映射为结构化失败。
由此任何 Agent 都可以注册进工具注册表，被其他 Agent 作为工具调用。
