# agentos-hitl

`agentos-hitl` 提供 Human-in-the-Loop（人在回路）能力，通过 `ToolInterceptor` 在高风险工具真正执行前插入风险判断和人工审批。

## 主要职责

- 根据工具声明的风险等级判断是否需要审批。
- 把待审批调用转换为标准审批请求。
- 通过可替换处理器连接审批页面、消息队列或外部工作流。
- 将审批结果返回计划执行器，决定继续执行还是拒绝步骤。

## 核心类型

### `RiskPolicy`

通过审批阈值判断工具是否需要人工确认。例如阈值为 `MEDIUM` 时：

| 工具风险 | 是否审批 |
| --- | --- |
| `LOW` | 否 |
| `MEDIUM` | 是 |
| `HIGH` | 是 |

### `ApprovalService`

负责创建 `ApprovalRequest`，并交给 `ApprovalHandler` 获取布尔审批结果。请求中包含会话、Agent、工具说明、调用参数和请求时间。

## 调用流程

```text
PlanExecutor
    └── ToolDispatcher
        ├── ApprovalToolInterceptor
        │   ├── 无需审批或已消费批准结果 ──► 继续
        │   └── 需要审批 ──► PendingAction(HUMAN_APPROVAL)
        └── AgentTool.execute(...)
```

## 默认安全策略

`agentos-server` 当前把审批阈值设置为 `MEDIUM`，并使用一个默认拒绝处理器。因此低风险工具可以执行，中高风险工具在接入真实审批渠道前保持阻断状态。

## 模块依赖与扩展

- `agentos-kernel`：使用 Agent 上下文标识审批来源。
- `agentos-tool`：读取工具风险等级和调用参数。

生产环境可以实现 `ApprovalHandler`，将请求发送到管理后台、企业 IM 或工作流系统。同步接口适合当前骨架；需要长时间等待时，可进一步扩展为持久化审批单和异步恢复机制。
