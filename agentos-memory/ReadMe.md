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


能力      L0 完整对话保存
当前状态  已实现
───────────────────────────────────────────────────────────────
能力      L1 原子记忆抽取
当前状态  部分实现，使用规则模型
───────────────────────────────────────────────────────────────
能力      L2 场景记忆
当前状态  已实现基础版本
───────────────────────────────────────────────────────────────
能力      L3 用户画像
当前状态  已实现基础版本
───────────────────────────────────────────────────────────────
能力      BM25 + 向量 + RRF
当前状态  部分实现；向量只是本地 Hashing，不是真实 Embedding
───────────────────────────────────────────────────────────────
能力      异步流水线、重试、恢复
当前状态  已实现基础版本
───────────────────────────────────────────────────────────────
能力      持久化
当前状态  仅自定义本地二进制文件
───────────────────────────────────────────────────────────────
能力      Agent 规划前召回、成功后写入
当前状态  已接入
───────────────────────────────────────────────────────────────
能力      Skill 创建、版本、资源、搜索、路由、对话抽取
当前状态  未实现
───────────────────────────────────────────────────────────────
能力      LLM-Wiki 文档解析、FTS5、知识图谱
当前状态  未实现
───────────────────────────────────────────────────────────────
能力      CodeGraph 仓库索引、符号调用、影响分析、自动同步
当前状态  未实现
───────────────────────────────────────────────────────────────
能力      Team/User/Agent/Task/Asset 管理
当前状态  未实现
───────────────────────────────────────────────────────────────
能力      private/team/restricted/agent ACL
当前状态  未实现；MemoryScope 只是数据隔离
───────────────────────────────────────────────────────────────
能力      Memory HTTP Gateway、OpenAPI
当前状态  未实现
───────────────────────────────────────────────────────────────
能力      /v3/tools/list、/v3/tools/call
当前状态  未实现
───────────────────────────────────────────────────────────────
能力      TypeScript/Python SDK 对应的 Java SDK/适配层
当前状态  未实现
───────────────────────────────────────────────────────────────
能力      Memory Panel、Proxy、OpenClaw/Hermes 插件
当前状态  未实现
───────────────────────────────────────────────────────────────
能力      SQLite、迁移工具、生产部署体系
当前状态  未实现

当前已有实现主要集中在：

- agentos-memory/src/main/java/com/github/agentos/memory/
  MemoryService.java:31：统一召回和写入入口。

- agentos-memory/src/main/java/com/github/agentos/memory/
  MemoryPipeline.java:35：L0→L1→L2→L3 异步处理。

- agentos-memory/src/main/java/com/github/agentos/memory/
  HybridMemoryRetriever.java:27：BM25、Hashing 向量和 RRF。

- agentos-memory/src/main/java/com/github/agentos/memory/
  RuleBasedMemoryModel.java:11：规则式抽取，并非真正的 LLM 记忆
  模型。

- agentos-server/src/main/java/com/github/agentos/server/
  AgentController.java:21：目前只有 Agent 运行接口，没有记忆资
  产管理接口。

测试结果也不能支持“全部完成”：

- agentos-memory 自身 2 个测试全部通过。
- 全项目执行 mvn test 失败：agentos-planner/src/test/java/com/
  github/agentos/planner/LlmTaskPlannerTest.java:57 对原子记忆
  召回的断言失败。

- 记忆模块只有一个测试类，尚未覆盖并发、失败重试、损坏文件、权
  限隔离、容量边界和真实 Embedding 等场景。

- agentos-memory/ReadMe.md:3 仍描述旧版 ShortMemory/LongMemory
  API，与当前代码已经不一致。

因此：

- 如果目标仅是“用 Java 实现基础 L0–L3 Chat Memory”，已经有一个
  可运行原型，但仍未达到生产完成度。

- 如果目标是“把 TencentDB-Agent-Memory 全部能力改写为 Java”，目
  前明显没有完成，Skill、Wiki、CodeGraph、Memory Hub 治理和服务
  化接口等核心部分尚未开始或没有落地。

