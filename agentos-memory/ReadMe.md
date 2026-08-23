# agentos-memory

`agentos-memory` 是 AgentOS 的 Java 原生分层记忆模块。它在 Agent 规划前召回有界上下文，
并在一次 Agent 运行成功后保存成功轮次快照、异步加工长期记忆。

## 记忆分层

| 层级 | 数据类型 | 作用域 | 用途 |
| --- | --- | --- | --- |
| L0 | `CompletedTurn` | team + user + agent + session + 兼容 task | 保存请求目标、最终回答和成功工具结果的有界摘要 |
| L1 | `AtomicMemory` | team + user + agent + 兼容 task | 保存事实、偏好、约束、决策等可检索记忆 |
| L2 | `ScenarioMemory` | team + user + agent + 兼容 task | 聚合任务或 Agent 场景摘要 |
| L3 | `ProfileMemory` | team + user + agent | 保存跨会话的稳定长期画像 |

空 `taskId` 表示通用记忆：指定任务可以召回同任务及通用记忆；使用空任务查询时可以查看
同一参与者下的全部任务记忆。Server API 还会把请求绑定到可信的 team/user 身份，并在读写时
执行所有权检查；这是一套 Memory API 最小 ACL，不等同于完整的团队成员与资源级 RBAC。

## 主链路

```text
成功的 Agent 轮次
  -> MemoryService.capture()
  -> 同事务保存 L0 + Pipeline Job（transactional outbox）
  -> MemoryPipeline: L1 -> L2 -> L3
  -> 持久化 PipelineJob 阶段和失败状态

下一次用户输入
  -> MemoryService.recall()
  -> L0 最近成功轮次 + L1 BM25/向量/RRF + L2 + L3
  -> 字符预算裁剪
  -> MemoryContext
```

只有完整成功的轮次应进入 `capture()`。主 Agent 捕获时保存请求目标、最终回答和成功 Observation
的有界摘要；它不是完整工具原始日志，不能用于严格事件重放。L1-L3 在后台单线程流水线中加工，
发生临时异常后会退避重试，默认 6 次是整个 Job 跨阶段的累计尝试次数。进程重启时，持久化存储
中的 `PENDING`、`RUNNING` 和可重试 `FAILED` 任务会恢复。召回超时或失败时返回
`degraded=true` 的空上下文，不阻断 Agent 主任务。

`MemoryStore.captureTurn()` 是 L0 与 Pipeline Job 的原子提交边界。SQLite 使用同一个 JDBC
事务，文件存储使用单次原子文件替换；进程重启后可从持久化 Job 恢复。调用方应使用稳定业务键
创建 `CompletedTurn`，避免网络重试产生重复 L0：

```java
CompletedTurn.success(scope, invocationId, input, output, toolOutputs);
```

## 快速使用

零配置内存模式：

```java
MemoryScope scope = new MemoryScope(
        "team-1", "user-1", "agent-1", "session-1", "coding");

try (MemoryService memory = MemoryService.inMemory()) {
    MemoryContext beforePlanning = memory.recall(scope, "我们使用什么语言？");

    memory.capture(CompletedTurn.success(
            scope,
            "我偏好简洁回答，项目必须使用 Java。",
            "明白，我会使用 Java 并保持简洁。",
            List.of("build succeeded")));

    memory.awaitIdle(Duration.ofSeconds(3));
}
```

内置工厂：

```java
MemoryService.inMemory();
MemoryService.persistent(Path.of(".agentos/memory"));
MemoryService.sqlite(Path.of(".agentos/memory/memory.sqlite"));
```

- `inMemory()`：仅当前 JVM 有效，适合单元测试。
- `persistent(directory)`：使用版本化二进制文件和原子替换，保留向后兼容能力。
- `sqlite(databaseFile)`：使用 JDBC、事务、索引和自动版本迁移，建议用于单实例持久化部署。

三个工厂默认使用 `RuleBasedMemoryModel`、`HashingMemoryEmbedding` 和
`MemoryRecallPolicy.defaults()`，无需外部模型服务。

## 真实 LLM 与 Embedding

模块提供不依赖 Spring 的 OpenAI-compatible HTTP 适配器：

```java
URI chatEndpoint = URI.create("https://api.openai.com/v1/chat/completions");
URI embeddingEndpoint = URI.create("https://api.openai.com/v1/embeddings");
String apiKey = System.getenv("OPENAI_API_KEY");

MemoryModel model = new OpenAiCompatibleMemoryModel(
        chatEndpoint, apiKey, "your-chat-model", Duration.ofSeconds(60));
MemoryEmbedding embedding = new OpenAiCompatibleMemoryEmbedding(
        embeddingEndpoint, apiKey, "your-embedding-model", Duration.ofSeconds(30));

MemoryStore store = new SqliteMemoryStore(
        Path.of(".agentos/memory/memory.sqlite"));
try (MemoryService memory = new MemoryService(
        store, model, embedding, MemoryRecallPolicy.defaults())) {
    // recall / capture
}
```

聊天适配器请求 JSON object 输出并校验 L1 候选字段；Embedding 适配器请求 float 向量并拒绝
空向量、非数字和非有限数值。HTTP 非 2xx、超时、中断及无效 JSON 统一抛出
`MemoryAdapterException`，随后由记忆流水线持久化失败状态并按策略重试。

兼容服务必须支持：

- Chat Completions 风格的 `messages`、`response_format={"type":"json_object"}` 及
  `choices[0].message.content`。
- Embeddings 风格的 `model`、`input`、`encoding_format=float` 及
  `data[0].embedding`。

不要把 API Key 写入仓库；应通过环境变量或外部密钥系统注入。不同兼容服务对
`response_format` 的支持可能不同，上线前应运行对应服务的契约测试。

原子记忆写入或正文修订时会生成文档向量，并按 `memoryId + model + contentVersion` 持久化；
召回只计算一次 Query Embedding，已有文档向量可跨重启复用。当前 `PersistentMemoryVectorIndex`
使用精确余弦搜索，消除了逐候选远程 Embedding 请求，但仍是单机线性扫描；大规模部署可实现同一
`MemoryVectorIndex` 端口，替换为 pgvector、Milvus 或 sqlite-vec 等 ANN 后端。

## JDBC / SQLite

`SqliteMemoryStore` 基于 `JdbcMemoryStore`，首次连接自动执行 classpath 中的迁移：

```text
V0__migration_history.sql  迁移历史表
V1__memory_core.sql        L0-L3 与 Pipeline Job 表
V2__memory_indexes.sql     作用域、时间和恢复队列索引
V3__memory_lifecycle_vectors.sql  生命周期、来源历史、业务幂等键和持久向量
```

当前 schema 版本可通过 `JdbcMemoryStore.schemaVersion()` 查询。迁移与版本记录在同一事务
提交，串行正常场景下重复初始化不会重复执行。当前迁移器尚未提供脚本 checksum、dirty 状态
和多实例迁移锁。主要索引覆盖：

- L0 的 team/user/agent/session/task + completed time；
- L1/L2 的 team/user/agent/task + updated time；
- Pipeline Job 的 status/attempts/updated time。

写入契约：

- `CompletedTurn.businessKey` 是 actor 维度的业务幂等键；稳定键会派生稳定 `id`，首次提交的
  成功轮次快照不会被重试覆盖。
- `captureTurn()` 同事务写入 L0 和待处理 Job，避免 orphan L0。
- L1/L2/L3 只有传入版本不低于当前版本时才更新；低版本不会回写，但同版本仍是 last-write-wins。
- L1 保存状态、有效期、到期时间、来源历史和 `supersededById`；普通召回只返回当前有效记录。
- 文档 Embedding 按模型和内容版本持久化，删除或清理原子记忆时由外键级联删除。
- L3 以 team/user/agent 唯一定位，不按 session 或 task 拆分。
- 单个存储方法中的复合写入使用 JDBC 事务；SQLite 启用外键和 busy timeout。

L3 虽然按 team/user/agent 唯一存储，但当前生成输入仍按 Pipeline Job 的 task 兼容范围查询；
不同 task 后完成的 Job 可能覆盖此前的 actor 画像。后续应按 task 保存子画像再合并，或使用 actor
全量记忆生成全局画像。

`JdbcMemoryStore` 当前使用 SQLite SQL 方言。若接入 PostgreSQL 或 MySQL，应提供独立方言
迁移和 upsert 语句，不能直接复用 SQLite 实现。

## Spring Server 配置

`agentos-server` 支持三种存储模式：

```yaml
agentos:
  memory:
    mode: sqlite # sqlite | file | memory
    data-dir: .agentos/memory
    database-file: .agentos/memory/memory.sqlite
    processor:
      mode: openai # openai | rule
      endpoint: ${AGENTOS_MEMORY_CHAT_ENDPOINT}
      api-key: ${AGENTOS_MEMORY_CHAT_API_KEY}
      model: ${AGENTOS_MEMORY_CHAT_MODEL}
      timeout: 60s
    embedding:
      mode: openai # openai | hashing
      endpoint: ${AGENTOS_MEMORY_EMBEDDING_ENDPOINT}
      api-key: ${AGENTOS_MEMORY_EMBEDDING_API_KEY}
      model: ${AGENTOS_MEMORY_EMBEDDING_MODEL}
      timeout: 30s
  security:
    api-key: ${AGENTOS_API_KEY}
    identity:
      team-id: default-team
      user-id: default-user
      roles: ""
      trust-headers: false
```

`data-dir` 只用于 `file`，`database-file` 只用于 `sqlite`。未知模式会在启动时直接报错，
避免配置拼写错误后静默切换存储。

`trust-headers=false` 时服务忽略调用方身份 Header，使用服务端固定身份。只有可信 API Gateway
已经完成认证并清洗 `X-AgentOS-Team-Id`、`X-AgentOS-User-Id`、`X-AgentOS-Roles` 时才可开启；
`MEMORY_ADMIN` 角色可跨 team/user 管理记忆。

## 生命周期 REST API

- `POST /api/memories/facts`：创建事实，可通过 `ttlSeconds` 设置 TTL；
- `PATCH /api/memories/atomic/{id}`：纠正正文与绝对到期时间；
- `PUT /api/memories/atomic/{id}/ttl`：设置 TTL，`ttlSeconds=null` 清除 TTL；
- `POST /api/memories/atomic/{id}/invalidate`：软失效并保留审计历史；
- `POST /api/memories/atomic/{id}/supersede`：创建替代记忆并关联旧记录；
- `DELETE /api/memories/atomic/{id}`：永久删除记忆、来源关系和向量；
- `GET /api/memories?...&includeInactive=true`：管理员视图可包含失效、替代和过期记录。

## 测试

```bash
mvn -pl agentos-memory test
```

当前测试覆盖：

- BM25/向量/RRF 相关性和类型过滤；
- team/user/agent/session/task 作用域边界；
- 内存与 SQLite 存储的一致契约；
- L0 幂等、L1-L3 版本保护及并发写入竞争；
- 临时模型失败重试、`RUNNING` 任务重启恢复和恢复顺序；
- 召回超时降级、字符预算和损坏文件；
- OpenAI-compatible Chat/Embedding 请求响应契约及 HTTP 错误；
- SQLite 迁移幂等、索引、重载和代表性性能回归预算。
- 生命周期、TTL、来源历史、supersede、事务 outbox、稳定业务幂等键和跨重启向量复用；
- Memory REST 写 API、请求身份绑定和跨身份 ACL 拒绝。

性能测试是防止明显退化的宽松回归门槛，不替代针对目标硬件、数据规模和并发模型的 JMH
或压测验收。

## 兼容性说明

早期未接入主链路的 `ShortMemory`、`LongMemory` 和 `MemoryEntry` 已删除。旧调用方应迁移到：

- 对话写入：`MemoryService.capture(CompletedTurn)`；
- 最近对话：`MemoryService.recentTurns(...)`；
- 手工长期事实：`MemoryService.rememberFact(...)`；
- 统一查询：`MemoryService.recall(...)` 或 `MemoryStore` 分层查询。

`MemoryService` 和 `MemoryPipeline` 持有执行器，使用完成后必须调用 `close()`；推荐使用
try-with-resources，Spring Bean 则配置 `destroyMethod = "close"`。

## 延伸文档

- [阶段一实现状态与上游能力差距](MEMORY_IMPLEMENTATION_STATUS.md)
- [分层记忆系统面试项目总结](../docs/AGENT_MEMORY_INTERVIEW.md)
