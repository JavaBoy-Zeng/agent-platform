# agentos-kernel

`agentos-kernel` 是 AgentOS 的最底层运行内核，负责定义一次 Agent 调用所需的上下文、状态、执行协议与横切运行时能力（事件、恢复、取消、插件、产物）。该模块不感知具体的规划器、工具、记忆或 Spring，其他模块围绕它进行组合。

## 主要职责

- 将本次请求、身份/任务上下文与运行时能力建模为不可变对象。
- 定义 Agent 循环的统一执行协议 `AgentLoop`，具体 Agent 通过实现它接入运行时。
- 按会话保存最新运行状态，对同一个 `sessionId` 的调用进行串行状态更新。
- 统一处理状态迭代、异常隔离和终态校验。
- 提供领域事件发布与持久化、审批 Checkpoint 恢复、协作式取消、用量记账插件和产物存储。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `AgentRequest` | 保存 `sessionId`、用户目标和自定义属性。 |
| `InvocationContext` | 单次执行的完整运行世界：团队/用户/Agent/任务身份、会话快照、执行预算、事件发布器、取消令牌和产物存储。 |
| `AgentExecutionLimits` | 单次运行的重规划、步骤、工具和模型调用累计预算。 |
| `AgentState` | Agent 的不可变状态快照，包含状态、迭代次数、输出、错误和更新时间。 |
| `AgentLoop` | 函数式执行协议，具体 Agent 通过实现它接入运行时。 |
| `AgentRunner` | Agent 的统一运行入口，按会话保存状态、组装 `InvocationContext` 并调用 `AgentLoop`。 |

## 横切运行时能力

| 类型 | 作用 |
| --- | --- |
| `AgentEventPublisher / AgentEventSink` | 领域事件发布端口；`StoringAgentEventPublisher` 同时落库，`StateMergingEventPublisher` 将状态增量合并进会话。 |
| `AgentEventStore` | 事件持久化端口；内存实现用于零依赖启动，SQLite 实现在 server 侧。 |
| `AgentCheckpoint / CheckpointStore` | 运行到审批等待点时的快照与存储；进程重启后凭 `invocationId` 恢复继续执行。 |
| `Session / SessionService / SessionState` | 会话身份、生命周期服务与结构化会话状态（会话历史派生的轮次视图）。 |
| `CancellationToken` | 协作式取消令牌：调用方触发 `cancel()`，循环与工具在检查点主动观察并进入 `CANCELLED` 终态。 |
| `AgentPlugin / AgentPluginManager` | 横切能力插件集合（用量记账、追踪等），在 Runner 执行边界与模型回调点统一分发，单插件异常被隔离。 |
| `Artifact / ArtifactService / LocalArtifactService` | 运行产物（报告、文档等非文本输出）的元数据、存储与分发；文件实现采用临时文件 + 原子替换写入，重启后仍可下载。 |
| `ModelUsage` | 一次模型调用的 token 用量快照，经插件体系汇入用量账本。 |

## 状态流转

```text
READY/上一轮终态
        │
        ▼
     RUNNING
        │
        ├── 正常完成 ──► COMPLETED
        ├── 执行异常 ──► FAILED
        ├── 等待审批 ──► WAITING（保存 Checkpoint，批准后恢复）
        └── 协作取消 ──► CANCELLED
```

## 调用方式

```java
AgentLoop loop = (request, context, state) ->
        state.complete("done: " + request.objective());
AgentRunner runner = new AgentRunner(loop);

AgentState result = runner.run(
        AgentRequest.of("session-1", "hello"),
        InvocationContext.of("main-agent"));
```

`AgentRunner` 在执行边界统一注入执行预算与产物存储（`bind`），并加载当前会话快照；
Runner 未装配产物存储时退化为 `ArtifactService.NOOP`，登记与查询恒为空。

## 依赖与边界

- 不依赖其他 AgentOS 业务模块。
- 不负责制定计划、执行工具或保存记忆。
- 不包含 Spring 注解，可以在命令行程序、测试或其他容器中独立使用。

新增 Agent 时通常不需要修改本模块，只需实现 `AgentLoop` 并交给 `AgentRunner`。
