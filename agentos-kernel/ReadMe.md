# agentos-kernel

`agentos-kernel` 是 AgentOS 的最底层运行内核，负责定义一次 Agent 调用所需的上下文、状态和执行协议。该模块不感知具体的规划器、工具、记忆或 Spring，其他模块可以围绕它进行组合。

## 主要职责

- 将本次请求与身份/任务上下文建模为两个不可变对象。
- 定义 Agent 循环的统一执行协议。
- 保存每个会话最新的运行状态。
- 统一处理状态迭代、异常隔离和终态校验。
- 对同一个 `sessionId` 的调用进行串行状态更新。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `AgentRequest` | 保存 `sessionId`、用户目标和自定义属性。 |
| `AgentContext` | 保存 `teamId`、`userId`、`agentId` 和 `taskId`。 |
| `AgentExecutionLimits` | 单次运行的重规划、步骤、工具和模型调用累计预算。 |
| `AgentState` | Agent 的不可变状态快照，包含状态、迭代次数、输出、错误和更新时间。 |
| `AgentLoop` | 函数式执行协议，具体 Agent 通过实现它接入运行时。 |
| `AgentRuntime` | Agent 的统一运行入口，按会话保存状态并调用 `AgentLoop`。 |

## 状态流转

```text
READY/上一轮终态
        │
        ▼
     RUNNING
        │
        ├── 正常完成 ──► COMPLETED
        └── 执行异常 ──► FAILED
```

`WAITING_APPROVAL` 和 `CANCELLED` 已在状态模型中预留，可在异步审批和任务取消能力中使用。

## 调用方式

```java
AgentLoop loop = (request, context, state) ->
        state.complete("done: " + request.objective());
AgentRuntime runtime = new AgentRuntime(loop);

AgentState result = runtime.run(
        AgentRequest.of("session-1", "hello"),
        AgentContext.of("main-agent"));
```

## 依赖与边界

- 不依赖其他 AgentOS 业务模块。
- 不负责制定计划、执行工具或保存记忆。
- 不包含 Spring 注解，可以在命令行程序、测试或其他容器中独立使用。

新增 Agent 时通常不需要修改本模块，只需实现 `AgentLoop` 并交给 `AgentRuntime`。
