# agentos-memory

`agentos-memory` 为 Agent 提供统一的记忆访问入口，当前包含短期会话窗口和长期事实存储两种内存型实现。

## 主要职责

- 记录用户、Agent、系统和工具消息。
- 为每个会话维护有容量限制的最近消息窗口。
- 保存需要长期保留的事实。
- 通过统一服务隔离上层 Agent 与具体存储实现。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `MemoryEntry` | 不可变记忆条目，包含角色、内容和创建时间。 |
| `ShortMemory` | 按 `sessionId` 保存有界消息队列，超出容量后淘汰最早条目。 |
| `LongMemory` | 按会话保存长期条目的内存型适配器。 |
| `MemoryService` | 统一门面，提供记录用户消息、Agent 消息和长期事实的方法。 |

## 数据流

```text
MainAgent
    │
    ▼
MemoryService
    ├── 对话消息 ──► ShortMemory
    └── 长期事实 ──► LongMemory
```

`ShortMemory` 使用并发 Map，并对单个会话的队列操作加锁；`LongMemory` 使用并发集合，因此当前实现可供单进程中的并发请求使用。

## 使用示例

```java
MemoryService memory = new MemoryService(new ShortMemory(20), new LongMemory());
memory.rememberUserMessage("session-1", "你好");
memory.rememberAssistantMessage("session-1", "你好，我是 AgentOS");
memory.rememberFact("session-1", "用户偏好中文回复");
```

## 生产环境扩展

当前 `LongMemory` 只保存在 JVM 内存中，进程重启后数据会丢失。生产环境可将其替换或抽象为数据库、Redis、对象存储或向量数据库适配器，并补充检索、摘要和记忆淘汰策略。

本模块不依赖其他 AgentOS 模块。
