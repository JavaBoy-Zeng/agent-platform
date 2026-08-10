# AgentOS 记忆系统实现状态

## 1. 文档目的

本文档记录 AgentOS 当前记忆系统的实现范围，并与
[TencentDB Agent Memory](https://github.com/TencentCloud/TencentDB-Agent-Memory)
当前公开能力进行对照。

核查结论：当前项目已经使用 Java 实现了一个可运行的 L0-L3 Chat Memory
内核，但尚未完成 TencentDB Agent Memory 全部能力的 Java 化移植。

本文档核查日期为 2026-08-10。本地核查基线为提交 `6c7368d`；上游对照范围为
核查当日 GitHub 默认展示的 `feat/server_team` 分支及其 README、MemoryCore、
MemoryKnowledge 能力说明。由于本次没有记录上游提交号，本文档适合作为当前差距
清单，不作为严格的版本兼容证明。正式验收前必须固定上游 tag 或 commit。

## 2. 总体结论

当前实现可分为三个层次：

1. 已实现基础能力：L0-L3 数据模型、异步加工流水线、基础持久化、混合检索、
   召回上下文组装以及与 Agent 执行链路的集成。
2. 部分实现能力：记忆抽取、Embedding、场景归纳、画像生成、基础持久化和
   检索质量。目前主要为零配置、单机和规则驱动实现。
3. 尚未实现能力：Skill、Wiki、CodeGraph、Memory Asset、团队权限治理、Memory
   HTTP Gateway、SDK、适配器、管理面板以及完整部署体系。

因此：

- 如果目标是“Java 版基础 L0-L3 Chat Memory”，当前已有可运行原型。
- 如果目标是“完整重写 TencentDB Agent Memory”，当前尚未完成。
- 当前实现不应作为 TencentDB Agent Memory 的完整 Java 等价实现发布。

状态定义：

- **已实现**：对应代码和直接单元测试存在，但不代表已完成完整端到端验收。
- **已实现基础版**：具备可运行的最小实现，尚缺生产级存储、性能、异常或兼容性保障。
- **部分实现**：只覆盖该能力的一部分语义，不能视为与上游等价。
- **已接入**：已经进入 AgentOS 调用链，但仍受底层实现完整度限制。
- **未实现**：当前仓库没有发现能够提供该能力的实现类或服务。

由于当前全项目测试并非全绿，表格中的“已实现”表示代码级实现状态，不表示整个
记忆系统已经通过发布验收。

## 3. 当前主链路

```text
LlmTaskPlanner
    │
    ├── 规划前调用 MemoryService.recall()
    │       ├── 查询 L0 最近对话
    │       ├── BM25 + Hashing Vector + RRF 查询 L1
    │       ├── 查询 L2 场景记忆
    │       ├── 查询 L3 核心画像
    │       └── MemoryContextFormatter 组装受限上下文
    │
    ▼
Agent 执行计划
    │
    ▼
MainAgent
    │
    └── 成功后调用 MemoryService.capture()
            ├── 按 CompletedTurn.id 幂等保存 L0
            └── MemoryPipeline 异步处理
                    ├── L1 原子记忆抽取、去重和修订
                    ├── L2 场景记忆聚合
                    └── L3 用户/Agent 画像归纳
```

## 4. 已实现及部分实现能力

| 能力 | 状态 | 主要实现类 | 实现说明 |
| --- | --- | --- | --- |
| 统一记忆服务入口 | 已实现 | [`MemoryService`](src/main/java/com/github/agentos/memory/MemoryService.java) | 提供召回、捕获、手工事实写入和分层数据查询入口。 |
| L0 完整对话模型 | 已实现 | [`CompletedTurn`](src/main/java/com/github/agentos/memory/CompletedTurn.java) | 保存用户输入、Agent 最终输出、工具输出、作用域及完成时间。 |
| L0 对话捕获 | 已实现 | [`MemoryPipeline`](src/main/java/com/github/agentos/memory/MemoryPipeline.java) | `capture()` 先保存完整对话，再创建异步处理任务。 |
| L0 最近对话查询 | 已实现 | [`InMemoryMemoryStore`](src/main/java/com/github/agentos/memory/InMemoryMemoryStore.java) | 按同一会话作用域查询最近完成轮次。 |
| L1 原子记忆模型 | 已实现 | [`AtomicMemory`](src/main/java/com/github/agentos/memory/AtomicMemory.java) | 支持类型、置信度、优先级、版本和来源轮次。 |
| L1 记忆语义类型 | 已实现 | [`MemoryType`](src/main/java/com/github/agentos/memory/MemoryType.java) | 包含事实、偏好、约束、决策、事件、经验和画像。 |
| L1 抽取扩展接口 | 已实现 | [`MemoryModel`](src/main/java/com/github/agentos/memory/MemoryModel.java) | 抽象原子记忆抽取、场景生成和画像生成能力。 |
| L1 规则式抽取 | 部分实现 | [`RuleBasedMemoryModel`](src/main/java/com/github/agentos/memory/RuleBasedMemoryModel.java) | 使用关键词和正则分类，不是真正的 LLM 抽取实现。 |
| L1 去重与修订 | 已实现基础版 | [`MemoryPipeline`](src/main/java/com/github/agentos/memory/MemoryPipeline.java) | 支持规范化精确去重、Jaccard 相似合并和版本递增。 |
| L1 手工事实写入 | 已实现 | [`MemoryService`](src/main/java/com/github/agentos/memory/MemoryService.java) | `rememberFact()` 可用于管理或迁移场景。 |
| L2 场景记忆模型 | 已实现 | [`ScenarioMemory`](src/main/java/com/github/agentos/memory/ScenarioMemory.java) | 按任务或 Agent 聚合场景摘要，并支持版本递增。 |
| L2 场景聚合 | 部分实现 | [`MemoryPipeline`](src/main/java/com/github/agentos/memory/MemoryPipeline.java)、[`RuleBasedMemoryModel`](src/main/java/com/github/agentos/memory/RuleBasedMemoryModel.java) | 当前为规则格式化，不具备真实 LLM 总结质量。 |
| L3 核心画像模型 | 已实现 | [`ProfileMemory`](src/main/java/com/github/agentos/memory/ProfileMemory.java) | 保存用户和 Agent 的稳定长期画像。 |
| L3 画像归纳 | 部分实现 | [`MemoryPipeline`](src/main/java/com/github/agentos/memory/MemoryPipeline.java)、[`RuleBasedMemoryModel`](src/main/java/com/github/agentos/memory/RuleBasedMemoryModel.java) | 当前从高优先级原子记忆和场景中规则化生成。 |
| BM25 关键词检索 | 已实现 | [`HybridMemoryRetriever`](src/main/java/com/github/agentos/memory/HybridMemoryRetriever.java) | 对 L1 原子记忆进行即时 BM25 排序。 |
| 向量检索 | 部分实现 | [`HybridMemoryRetriever`](src/main/java/com/github/agentos/memory/HybridMemoryRetriever.java)、[`MemoryEmbedding`](src/main/java/com/github/agentos/memory/MemoryEmbedding.java) | 已定义 Embedding 扩展口，但没有真实模型适配器。 |
| 默认 Hashing 向量 | 已实现基础版 | [`HashingMemoryEmbedding`](src/main/java/com/github/agentos/memory/HashingMemoryEmbedding.java) | 用于零配置和测试，不能视为真正语义 Embedding。 |
| RRF 混合排序 | 已实现 | [`HybridMemoryRetriever`](src/main/java/com/github/agentos/memory/HybridMemoryRetriever.java) | 融合 BM25 和向量召回排名。 |
| 中英文基础分词 | 已实现基础版 | [`TextAnalyzer`](src/main/java/com/github/agentos/memory/TextAnalyzer.java) | 英文按词、中文按单字和二元组进行无依赖分词。 |
| 检索请求和结果模型 | 已实现 | [`MemoryQuery`](src/main/java/com/github/agentos/memory/MemoryQuery.java)、[`MemorySearchHit`](src/main/java/com/github/agentos/memory/MemorySearchHit.java) | 支持作用域、记忆类型、数量限制、评分和来源。 |
| 记忆作用域隔离 | 已实现基础版 | [`MemoryScope`](src/main/java/com/github/agentos/memory/MemoryScope.java) | 使用 team、user、agent、session、task 标识控制查询范围，但不等于 ACL。 |
| 召回预算与超时 | 已实现 | [`MemoryRecallPolicy`](src/main/java/com/github/agentos/memory/MemoryRecallPolicy.java) | 限制最近对话数、原子记忆数、场景数、字符数和超时时间。 |
| 记忆上下文模型 | 已实现 | [`MemoryContext`](src/main/java/com/github/agentos/memory/MemoryContext.java) | 统一封装 L0-L3 召回结果、格式化文本及降级状态。 |
| 上下文边界及截断 | 已实现 | [`MemoryContextFormatter`](src/main/java/com/github/agentos/memory/MemoryContextFormatter.java) | 添加不可信历史数据边界，并根据字符预算截断。 |
| 异步 L1-L3 流水线 | 已实现 | [`MemoryPipeline`](src/main/java/com/github/agentos/memory/MemoryPipeline.java) | 使用单线程调度器依次执行 L1、L2、L3。 |
| Pipeline 状态持久化 | 已实现 | [`PipelineJob`](src/main/java/com/github/agentos/memory/PipelineJob.java)、[`MemoryStore`](src/main/java/com/github/agentos/memory/MemoryStore.java)、[`FileMemoryStore`](src/main/java/com/github/agentos/memory/FileMemoryStore.java) | 保存阶段、状态、尝试次数、错误和更新时间；文件模式下可跨进程重启加载。 |
| 失败重试与启动恢复 | 已实现基础版 | [`MemoryPipeline`](src/main/java/com/github/agentos/memory/MemoryPipeline.java) | 最多重试六次，使用退避延迟恢复未完成任务。 |
| 统一存储接口 | 已实现 | [`MemoryStore`](src/main/java/com/github/agentos/memory/MemoryStore.java) | 定义 L0-L3 和 Pipeline Job 的存取端口。 |
| JVM 内存存储 | 已实现 | [`InMemoryMemoryStore`](src/main/java/com/github/agentos/memory/InMemoryMemoryStore.java) | 适用于测试和单进程运行。 |
| 本地文件持久化 | 已实现基础版 | [`FileMemoryStore`](src/main/java/com/github/agentos/memory/FileMemoryStore.java) | 使用带版本号的自定义二进制格式和原子文件替换。 |
| 规划前记忆召回 | 已接入 | [`LlmTaskPlanner`](../agentos-planner/src/main/java/com/github/agentos/planner/LlmTaskPlanner.java) | 构建规划请求前调用 `MemoryService.recall()`。 |
| 成功后记忆捕获 | 已接入 | [`MainAgent`](../agentos-agent/src/main/java/com/github/agentos/agent/MainAgent.java) | Agent 执行成功后保存本轮输入、输出和工具结果。 |
| Spring 运行配置 | 已接入 | [`AgentOsConfiguration`](../agentos-server/src/main/java/com/github/agentos/server/AgentOsConfiguration.java) | 支持 `memory` 和本地文件两种运行模式。 |

## 5. 已实现能力的类级入口

### 5.1 记忆写入入口

```java
MemoryService.capture(CompletedTurn turn)
```

这里的幂等边界是 `CompletedTurn.id`：`MemoryStore.saveTurn()` 对相同 ID 使用
`putIfAbsent()`。`CompletedTurn.success()` 每次会生成随机 UUID，因此只有调用方重用
同一个轮次 ID 时才能避免重复写入；当前实现不保证业务语义上的 exactly-once。

调用链：

```text
MainAgent.run()
  -> MemoryService.capture()
  -> MemoryPipeline.capture()
  -> MemoryStore.saveTurn()
  -> PipelineJob.pending()
  -> MemoryPipeline.processL1()
  -> MemoryPipeline.processL2()
  -> MemoryPipeline.processL3()
```

### 5.2 记忆召回入口

```java
MemoryService.recall(MemoryScope scope, String currentInput)
```

调用链：

```text
LlmTaskPlanner.createPlan()
  -> MemoryService.recall()
  -> MemoryStore.listRecentTurns()
  -> HybridMemoryRetriever.search()
  -> MemoryStore.listScenarios()
  -> MemoryStore.findProfile()
  -> MemoryContextFormatter.format()
  -> PlanningRequest
```

`MemoryStore` 当前有 `InMemoryMemoryStore` 和 `FileMemoryStore` 两种实现，召回主链路
依赖存储接口，不固定绑定某一个实现类。

### 5.3 默认实例创建入口

```java
MemoryService.inMemory()
MemoryService.persistent(Path directory)
```

两个工厂方法当前默认组装：

```text
RuleBasedMemoryModel
+ HashingMemoryEmbedding
+ MemoryRecallPolicy.defaults()
+ InMemoryMemoryStore 或 FileMemoryStore
```

## 6. 尚未实现的上游能力

以下能力在当前项目中没有对应 Java 实现类。

| 上游能力 | 当前状态 | 建议的 Java 模块或核心类型 |
| --- | --- | --- |
| Skill 创建、修改、删除和查询 | 未实现 | `SkillService`、`SkillRepository`、`SkillController` |
| Skill 版本管理 | 未实现 | `SkillVersion`、`SkillVersionService` |
| Skill 资源文件 | 未实现 | `SkillResource`、`SkillResourceStore` |
| Skill 搜索、路由和对话抽取 | 未实现 | `SkillRetriever`、`SkillRouter`、`SkillExtractionPipeline` |
| Wiki 文档上传和拉取 | 未实现 | `WikiIngestionService`、`DocumentSourceFetcher` |
| Wiki LLM 页面生成 | 未实现 | `WikiGenerationPipeline`、`WikiPage` |
| Wiki 全文检索和链接图谱 | 未实现 | `WikiSearchService`、`WikiLinkGraph` |
| CodeGraph Git 仓库同步 | 未实现 | `RepositorySyncService`、`GitSourceFetcher` |
| CodeGraph 文件及符号索引 | 未实现 | `CodeGraphIndexer`、`CodeSymbol`、`CodeFile` |
| 调用关系及影响分析 | 未实现 | `CallGraphService`、`ImpactAnalysisService` |
| Knowledge Auto-Sync | 未实现 | `KnowledgeSyncScheduler`、`KnowledgeBuildQueue` |
| Memory Asset 统一模型 | 未实现 | `MemoryAsset`、`AssetType`、`AssetStatus` |
| Asset 版本、状态、所有权和使用统计 | 未实现 | `AssetVersion`、`AssetOwnershipService`、`AssetUsageService` |
| Agent 与资产装配关系 | 未实现 | `AgentAssetBinding`、`AgentLoadoutService` |
| Team、User、Role 和 Membership | 未实现 | `Team`、`User`、`Role`、`TeamMembership` |
| private/team/restricted/agent 可见性 | 未实现 | `AssetVisibility`、`AccessPolicy` |
| User/Role/Agent ACL | 未实现 | `AssetAcl`、`AuthorizationService` |
| Memory HTTP Gateway | 未实现 | `MemoryController`、`MemoryAdminController` |
| `/v3/tools/list`、`/v3/tools/call` | 未实现 | `MemoryToolController`、`MemoryToolRegistry` |
| Knowledge HTTP API 和状态回调 | 未实现 | `KnowledgeController`、`KnowledgeCallbackClient` |
| OpenAPI 文档 | 未实现 | SpringDoc/OpenAPI 配置与接口注解 |
| Java SDK | 未实现 | 独立 `agentos-memory-sdk` 模块 |
| OpenClaw/Hermes/Claude Code 适配器 | 未实现 | 独立适配器模块 |
| SQLite/JDBC 数据库持久化 | 未实现 | JDBC/JPA/MyBatis Repository 实现 |
| 数据库版本迁移 | 未实现 | Flyway 或 Liquibase 迁移脚本 |
| Memory Hub 管理面板 | 未实现 | 后端管理 API 及对应前端页面 |
| Proxy 服务和模型绑定 | 未实现 | `MemoryProxyService`、`LlmBindingService` |
| 生产部署、健康检查和可观测性 | 未实现 | Actuator、指标、Trace、Docker 部署配置 |

表格中的建议类名仅用于规划，不代表这些类已经存在。

## 7. 旧版类说明

以下类属于项目早期的短期/长期记忆实现：

- [`MemoryEntry`](src/main/java/com/github/agentos/memory/MemoryEntry.java)
- [`ShortMemory`](src/main/java/com/github/agentos/memory/ShortMemory.java)
- [`LongMemory`](src/main/java/com/github/agentos/memory/LongMemory.java)

它们目前没有接入新的 `MemoryService`、`MemoryPipeline` 和 `MemoryStore` 主链路。
现有 [`agentos-memory/ReadMe.md`](ReadMe.md) 仍主要描述这套旧 API，
其中的构造示例与当前 `MemoryService` 已不一致，应当在后续重构中更新或删除。

## 8. 测试状态

### 8.1 记忆模块测试

执行命令：

```bash
mvn -pl agentos-memory test
```

结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

对应测试类：

- [`MemoryServiceTest`](src/test/java/com/github/agentos/memory/MemoryServiceTest.java)

当前仅验证：

- L0 可以在同一会话召回，L1-L3 可以在同一用户和 Agent 范围内跨会话召回。
- 文件持久化后可以重新加载 L0-L3 状态。

### 8.2 全项目测试

执行命令：

```bash
mvn test
```

结果：`BUILD FAILURE`。

失败位置：

- [`LlmTaskPlannerTest`](../agentos-planner/src/test/java/com/github/agentos/planner/LlmTaskPlannerTest.java)
  的 `recallsLayeredMemoryBeforeCallingTheModel()`。
- 失败断言要求输入 `hello` 时仍能召回非空 L1 原子记忆。
- 当前 `HybridMemoryRetriever` 只保留 BM25 得分大于零或向量余弦得分大于零的结果，
  因此该断言与当前相关性过滤行为不一致。

这可能是测试预期错误，也可能说明召回缺少稳定记忆兜底策略。在明确产品语义前，不能据此
直接修改检索算法。

### 8.3 尚缺测试

至少还需要补充：

- Pipeline 各阶段失败、退避重试和达到最大次数后的状态测试。
- 服务重启时对 `PENDING`、`RUNNING`、`FAILED` Job 的恢复测试。
- 同一轮次重复提交的幂等测试。
- 多线程写入、读取和文件持久化一致性测试。
- 损坏文件、截断文件和未知格式版本测试。
- team/user/agent/session/task 作用域隔离边界测试。
- BM25、向量召回和 RRF 排序的确定性测试。
- 字符预算、单条截断和召回超时降级测试。
- 记忆抽取误判、冲突事实和版本演进测试。
- Agent 运行失败时不写入 L0 的集成测试。
- 真实 Embedding 和真实 LLM MemoryModel 的契约测试。

## 9. 完成完整 Java 化所需的建议阶段

### 阶段一：稳定现有 Chat Memory

1. 修复全项目失败测试并明确无相关性查询的召回策略。
2. 清理或迁移 `ShortMemory`、`LongMemory` 和 `MemoryEntry` 旧实现。
3. 更新 `agentos-memory/ReadMe.md`，使其与当前 API 一致。
4. 增加真实 LLM `MemoryModel` 实现和真实 Embedding 适配器。
5. 增加 JDBC/SQLite 存储、索引和数据库迁移。
6. 补齐异常恢复、并发、幂等和性能测试。

### 阶段二：实现 Memory Asset 与治理

1. 建立统一的 Chat Memory、Skill、Wiki、CodeGraph Asset 模型。
2. 实现用户、团队、Agent、角色和成员关系。
3. 实现资产所有权、版本、状态、可见性、ACL 和 Agent 装配。
4. 提供管理 REST API、OpenAPI 和 Java SDK。

### 阶段三：实现 Skill

1. 实现 Skill 内容、版本和资源文件存储。
2. 实现搜索、路由、触发边界和 Agent 装配。
3. 实现从对话及工具调用中抽取 Skill 的异步流水线。
4. 实现审核、发布、共享和撤销流程。

### 阶段四：实现 Wiki 与 CodeGraph

1. 实现文档和 Git 仓库导入。
2. 实现 Wiki 页面生成、全文检索和链接图谱。
3. 实现代码文件、符号、调用关系和影响范围索引。
4. 实现构建队列、状态回调、失败恢复和自动同步。
5. 实现 `/v3/tools/list` 和 `/v3/tools/call`。

### 阶段五：平台化与兼容性

1. 实现 Memory Hub 管理界面。
2. 实现 OpenClaw、Hermes、Claude Code 等适配器。
3. 实现 Proxy、模型绑定和多模型配置。
4. 补充健康检查、监控、Trace、备份、迁移和容器化部署。
5. 建立与固定上游版本对应的功能验收矩阵和回归测试。

## 10. 验收标准建议

只有满足以下条件后，才建议声明“完整 Java 化已实现”：

1. 上游四类资产 Chat Memory、Skill、Wiki、CodeGraph 均有 Java 实现。
2. L0-L3 生成、检索、版本演进和恢复行为通过稳定测试。
3. 团队、用户、Agent、资产、所有权、角色和 ACL 语义完整。
4. HTTP Gateway、工具调用接口、OpenAPI 和 Java SDK 可用。
5. 文档、代码仓库和历史对话均可以冷启动导入。
6. 数据库迁移、备份恢复、健康检查和可观测性达到生产要求。
7. 全项目测试通过，并存在覆盖四类资产端到端流程的集成测试。
8. 与一个明确的 TencentDB Agent Memory 上游版本建立逐项验收记录。
