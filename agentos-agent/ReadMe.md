# agentos-agent

`agentos-agent` 是 AgentOS 的业务编排层，负责在运行预算内循环执行“规划 → 工具 → 重新规划”，
并只在得到明确最终回答后完成会话。

## `MainAgent` 流程

```text
AgentRuntime.run(AgentRequest, AgentContext)
    └── MainAgent
        ├── AgentPlanner.createPlan(...)
        ├── PlanExecutor.execute(...)
        ├── AgentPlanner.replan(..., PlanExecutionSnapshot)
        │       └── 可重复，受累计预算限制
        ├── AgentFinalizer.finish(EXECUTION / COMPLETE)
        └── MemoryService.capture(CompletedTurn.success)
```

`AgentRequest` 保存 `sessionId`、用户目标和扩展属性；`AgentContext` 只保存团队、用户、Agent 和
任务身份。两者在内核、规划器和 Agent 循环中始终分开传递。

`MainAgent` 在以下情况请求重规划：

- `DISCOVERY_COMPLETED`：探索计划成功并获得新环境信息。
- `RECOVERABLE_FAILURE`：工具失败但任务仍可恢复。
- `INVALID_ASSUMPTION`：例如原计划猜测的文件不存在。
- `EXECUTION_COMPLETED`：工具执行完成，需要模型综合真实结果。

只有 `EXECUTION / COMPLETE` 会进入 `AgentFinalizer`。Finalizer 是 Runtime 内部控制动作，
不会出现在工具注册表中，也不会额外调用模型。

记忆采用 fail-open：只有最终成功的运行会写入 `CompletedTurn`；记忆存储失败不会把成功运行改成
失败。工具观察写入记忆前按单条 20,000 字符、总计 100,000 字符限制，并优先保留最新结果。
