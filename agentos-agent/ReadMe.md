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
├── loop/        PlanExecuteAgent / SimpleQaAgent 等可执行实现
├── routing/     分层作用域、能力匹配、接单校验与安全回退
├── strategy/    执行策略路由（DIRECT / REACT / PLAN）
└── workflow/    BaseAgent 体系与 Workflow Agents
```

依赖只能自上而下：根包不依赖任何子包；`registry`、`finalize` 是底层设施；
`loop` 是可执行实现的叶子包；`routing` 与 `strategy` 互不依赖。
规则由 `AgentModuleArchitectureTest` 守护。

## `PlanExecuteAgent` 流程

```text
AgentRunner.run(AgentRequest, InvocationContext)
    └── PlanExecuteAgent
        ├── AgentPlanner.createPlan(...)
        ├── PlanExecutor.execute(...)
        ├── AgentPlanner.replan(..., PlanExecutionSnapshot)
        │       └── 可重复，受累计预算限制
        ├── AgentFinalizer.finishStreaming(EXECUTION / COMPLETE)
        └── MemoryService.capture(CompletedTurn.success)
```

`AgentRequest` 保存 `sessionId`、用户目标和扩展属性；`InvocationContext` 整合团队、用户、
Agent、任务身份、会话状态与执行预算，是单次执行的完整运行世界。

`PlanExecuteAgent` 在以下情况请求重规划：

- `DISCOVERY_COMPLETED`：探索计划成功并获得新环境信息。
- `RECOVERABLE_FAILURE`：工具失败但任务仍可恢复。
- `INVALID_ASSUMPTION`：例如原计划猜测的文件不存在。
- `EXECUTION_COMPLETED`：工具执行完成，需要模型综合真实结果。

只有 `EXECUTION / COMPLETE` 会进入 `AgentFinalizer`。Finalizer 是 Runner 内部控制动作，
不会出现在工具注册表中。生产装配使用文本模型 SSE 生成最终回答，每个可见增量直接变成
`OUTPUT_DELTA`，该额外模型调用计入运行预算。

记忆采用 fail-open：只有最终成功的运行会写入 `CompletedTurn`；记忆存储失败不会把成功运行改成
失败。工具观察写入记忆前按单条 20,000 字符、总计 100,000 字符限制，并优先保留最新结果。

`loop` 包中的 `ContinuationStore` 保存断点续跑状态：运行在审批等待、协作取消等中断点
保存上下文，进程重启（sqlite 持久化模式）后凭 `invocationId` 恢复继续执行。
`SimpleQaAgent` 是简单问答的直答实现：单轮响应、不携带工具定义，配合
`SessionHistoryService` 注入的最近轮次支持指代消解。

路由层先处理确定性的本地运行时自省，再由 Supervisor 以结构化 JSON 描述 `scope`、
`requiredCapabilities`、`targetAgent` 和 `confidence`。实现 `RoutableAgent` 的专家在产生
任何副作用前执行接单校验；不匹配时回退配置的默认编排 Agent（缺省 ReactAgent），低置信度且作用域有歧义时直接询问用户。
`SearchAgent` 只接受 `EXTERNAL_WORLD + WEB_RESEARCH`，并在内部再次拒绝当前 AgentOS
目录问题，确保错误直派也不会调用网络工具。

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
Agent。COMPLETED 映射为工具成功；WAITING 保存子检查点并向父循环传递实际审批操作，
批准后恢复原子任务，拒绝时清理恢复数据，其他终态映射为结构化失败。
由此任何 Agent 都可以注册进工具注册表，被其他 Agent 作为工具调用。

### SearchAgent 的可靠性门槛

`SearchAgent` 默认装配 `browser_search` 与 `web_fetch`。普通问题先搜索候选链接，再读取
不同站点的正文；目标中包含明确 URL 时会跳过关键词生成和搜索，直接读取该页面。它不会
在搜索服务缺失时让模型猜测 URL。一次普通多来源检索需要满足：

- 从搜索结果中提取候选链接，并按域名去重；
- 自动抓取并切换候选来源，至少获得两个不同站点的有效正文；
- 每个正文至少包含 80 个可读字符；
- 模型请求或工具遇到 HTTP 429、5xx、超时时，最多重试三次并指数退避；
- 来源不足时发出 `ABORT / INSUFFICIENT_SEARCH_EVIDENCE` 和 `RUN_FAILED`，不会标记
  `COMPLETE`，也不会继续让模型凭自身知识补齐答案。

生产环境需要部署 SearXNG（`docker/searxng/` 提供启动配置），并通过
`AGENTOS_BROWSER_SEARCH_ENDPOINT` 指向其搜索端点。


### 编排层与专才执行边界

生产 PlanExecuteAgent / ReactAgent 只获得 Agent 委派清单；Dispatcher 同时阻止原始工具直调。
`ResumableSpecialist` 为内置专才和配置专才提供模型结果、工具结果与非确定性参数的持久化重放日志，
让审批恢复不重新生成文档或重复已完成的写入。实际工具统一经过 Dispatcher 的权限与审批拦截。
`ToolProviderAgent` 按 Invocation 接入工具集合，默认装配 workspace-agent 与 utility-agent。
详细执行及恢复约定见 [路由流程](../docs/ROUTING_FLOW.md#51-编排与工具执行边界)。
