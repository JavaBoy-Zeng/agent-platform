# AgentStreamEvent 流式协议

AgentOS 对客户端只暴露自有的 `AgentStreamEvent`。OpenAI、Anthropic、Gemini 等模型厂商的原始流格式、系统提示词和隐藏推理只能停留在模型适配层或服务端诊断链路，不能进入对话 SSE。

旧接口 `/api/agents/runs/stream` 和 `/api/agents/runs/event-stream` 已移除，不提供兼容层。

## HTTP 流程

1. `POST /api/agent-runs` 创建后台 Run，返回 `202 Accepted` 和 `AgentRunSnapshot`。
2. `GET /api/agent-runs/{runId}` 查询最终事实快照。
3. `GET /api/agent-runs/{runId}/events?afterSeq=N` 补播 `N` 之后的事件并继续 SSE 订阅。
4. 客户端也可以发送 `Last-Event-ID: N`；服务端取它与 `afterSeq` 的较大值。
5. `POST /api/agent-runs/{runId}/cancel` 执行真实协作式取消。
6. `POST /api/agent-runs/invocations/{invocationId}/resolution` 在原 Run 上解决审批并恢复，`runId`、`turnId`、`seq` 不重置。
7. `GET /api/agent-runs/history?sessionId=...` 只返回可持久化的用户事件，用于重建会话。

```bash
curl -X POST http://localhost:8080/api/agent-runs \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"session-1","input":"检查并修复测试"}'

curl -N 'http://localhost:8080/api/agent-runs/{runId}/events?afterSeq=0'
```

## 事件信封

```json
{
  "schemaVersion": "1",
  "event": "message.delta",
  "eventId": "evt_23f0...",
  "runId": "run_123",
  "turnId": "turn_456",
  "sessionId": "session_1",
  "itemId": "msg_789",
  "agentId": "react-agent",
  "parentRunId": "",
  "seq": 18,
  "timestamp": "2026-09-10T12:45:00Z",
  "visibility": "USER",
  "data": {
    "delta": "正在检查当前实现",
    "offset": 0,
    "contentLength": 8
  }
}
```

- `runId`：一次完整执行。
- `turnId`：一次 user → assistant 轮次。
- `itemId`：同一 message、tool call、artifact 生命周期的稳定标识。
- `eventId`：全局唯一，供跨连接去重与审计。
- `seq`：单个 Run 内严格递增；终态后不再产生正常业务事件。
- `agentId`、`parentRunId`：保留多 Agent 来源和父子 Run 关系。
- `data`：有界、面向客户端的结构化数据。

## Run 状态机

```text
CREATED → RUNNING ⇄ WAITING
              └──→ COMPLETED
              └──→ FAILED
              └──→ CANCELLED
```

`COMPLETED`、`FAILED`、`CANCELLED` 是终态。`WAITING` 不是终态，可等待工具审批、用户补参或子 Agent。用户在 `WAITING` 时仍可取消 Run。

## 事件类型与持久化

| 事件 | 是否持久化 | 说明 |
| --- | --- | --- |
| `run.started` | 是 | Run 开始及用户目标 |
| `run.waiting` | 是 | Run 进入 WAITING，携带完整快照 |
| `run.completed` / `run.failed` / `run.cancelled` | 是 | Run 终态，携带最终快照 |
| `status` | 否 | 用户可理解的临时阶段，不含 Chain of Thought |
| `message.started` | 是 | 创建 Assistant 消息项 |
| `message.delta` | 否 | 高频文本增量，仅用于实时体验 |
| `message.completed` | 是 | 权威完整正文，用于修正漏包和历史回放 |
| `tool.started` | 是 | 工具生命周期开始，携带 `toolCallId` |
| `tool.input.delta` | 否 | 可选的工具参数增量 |
| `tool.awaiting_approval` / `tool.approved` | 是 | 工具审批生命周期 |
| `tool.completed` / `tool.failed` | 是 | 工具结果；失败不等于 Run 失败 |
| `artifact.created` | 是 | 可下载产物元数据 |
| `usage` | 是 | 本次及累计 token 使用量 |
| `error` | 是 | 非终态结构化错误信息 |

持久化层不逐 token 写库。PostgreSQL 保存 Run 快照和关键事件；`status`、`message.delta`、`tool.input.delta` 只进入实时流和有界内存窗口。

## 最终快照

`message.completed` 的 `data.content` 是消息最终事实；Run 终态事件还会在 `data.snapshot` 中携带完整 `AgentRunSnapshot`：

```json
{
  "event": "run.completed",
  "seq": 42,
  "data": {
    "snapshot": {
      "schemaVersion": "1",
      "runId": "run_123",
      "turnId": "turn_456",
      "status": "COMPLETED",
      "lastSeq": 42,
      "durationMs": 248000,
      "output": "完整的最终回答",
      "error": null,
      "usage": {
        "inputTokens": 1367,
        "outputTokens": 404,
        "cachedTokens": 175616,
        "totalTokens": 177387,
        "modelCalls": 4
      }
    }
  }
}
```

客户端不能假设所有 delta 都送达，收到 `message.completed` 和终态 snapshot 后必须以完整内容校正本地状态。

## 断线恢复与前端归约

前端持久保存每个活动 Run 的 `lastSeq`。重连时传 `afterSeq=lastSeq`，并按以下规则消费：

1. `seq <= lastSeq` 直接忽略。
2. 按 `itemId` 更新现有消息、工具或产物，不能无条件 append。
3. `message.delta` 先累计，再按 30–80ms 节流刷新 Markdown。
4. `message.completed` 覆盖本地累积正文。
5. `status` 只更新临时阶段 UI，不写入 conversation history。
6. Run 终态后关闭订阅；刷新页面可从持久化事件与终态 snapshot 重建。

服务端重启后，已完成 Run 仍能查询和补播关键事件。运行中的模型请求或 Shell 进程不会被“自动复活”；当前版本也不会在重启后重新装载 WAITING Run，客户端应展示已保存快照并由用户发起新 Run。

## 工具结果边界

工具输出必须有界。大结果只在事件中返回摘要与引用：

```json
{
  "event": "tool.completed",
  "itemId": "tool_123",
  "data": {
    "toolCallId": "tool_123",
    "toolName": "run_command",
    "summary": "Tests: 231 passed",
    "outputRef": "output_456",
    "truncated": true
  }
}
```

错误使用稳定 `code` 供程序判断、`message` 供用户阅读、`retryable` 供重试策略判断。客户端不得解析错误文本推断类型。
