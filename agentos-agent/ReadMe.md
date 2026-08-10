# agentos-agent

`agentos-agent` 是 AgentOS 的业务编排层。它把内核、规划器和记忆服务连接起来，形成一次完整的 Agent 循环。

## 主要职责

- 接收 `AgentRuntime` 传入的上下文和运行状态。
- 在执行前记录用户消息。
- 调用 `TaskPlanner` 创建任务计划。
- 调用 `PlanExecutor` 执行计划中的步骤。
- 根据执行结果把状态转换为 `COMPLETED` 或 `FAILED`。
- 在成功后保存 Agent 回复。

## 核心类型

### `MainAgent`

当前模块的默认 Agent，实现了内核中的 `AgentLoop`：

```text
AgentRuntime
    │
    ▼
MainAgent
    ├── MemoryService.rememberUserMessage(...)
    ├── TaskPlanner.createPlan(...)
    ├── PlanExecutor.execute(...)
    └── MemoryService.rememberAssistantMessage(...)
```

`MainAgent` 本身不关心计划由规则还是 LLM 生成，也不直接执行工具或处理审批，这些能力分别由 planner、tool 和 hitl 模块负责。

## 模块依赖

- `agentos-kernel`：实现 `AgentLoop`，使用上下文和状态模型。
- `agentos-planner`：生成并执行计划。
- `agentos-memory`：记录用户输入和 Agent 输出。

## 扩展方式

- 替换 `TaskPlanner` 可以接入 LLM、工作流引擎或规则系统。
- 替换 `MemoryService` 后端可以接入数据库或向量存储。
- 可以新增其他 `AgentLoop` 实现，例如研究 Agent、代码 Agent 或多 Agent 调度器，而不必修改 `AgentRuntime`。
