# AgentOS

AgentOS 是一个基于 Java 21、Maven 与 Spring Boot 的多模块 Agent 运行时骨架。各领域模块保持为纯 Java，Spring 只负责在 `agentos-server` 中完成装配和对外服务。

## 模块与依赖

```mermaid
flowchart LR
    console[agentos-console] -->|HTTP /api| server[agentos-server]
    server[agentos-server] --> agent[agentos-agent]
    server --> tool[agentos-tool]
    server --> hitl[agentos-hitl]
    agent --> kernel[agentos-kernel]
    agent --> planner[agentos-planner]
    agent --> memory[agentos-memory]
    planner --> kernel
    planner --> tool
    planner --> memory
    hitl --> kernel
    hitl --> tool
    tool --> kernel
```

- [`agentos-kernel`](agentos-kernel/ReadMe.md)：运行入口（`AgentRunner`/`InvocationContext`）、Agent 循环、状态机，以及事件、Checkpoint 恢复、协作式取消、插件与产物存储等横切能力。
- [`agentos-agent`](agentos-agent/ReadMe.md)：主 Agent 编排与三级意图路由，连接规划、执行和记忆；含 BaseAgent 体系与 Workflow Agents（串行/并行/循环编排、Agent 工具化）。
- [`agentos-planner`](agentos-planner/ReadMe.md)：迭代规划、失败分类、计划模型、执行器和 LlmFlow 请求组装流水线。
- [`agentos-tool`](agentos-tool/ReadMe.md)：工具 API（`ToolContext`）、统一调度运行时、结构化失败、产物登记和内置工具。
- [`agentos-memory`](agentos-memory/ReadMe.md)：L0–L3 分层记忆、混合召回、持久化 Pipeline 与统一服务。
- [`agentos-hitl`](agentos-hitl/ReadMe.md)：风险策略与人工审批端口；shell 命令按内容级策略放行只读命令、审批破坏性命令。
- [`agentos-console`](agentos-console/ReadMe.md)：基于 Vue 3 的独立 Agent 操作控制台。
- [`agentos-server`](agentos-server/ReadMe.md)：Spring Boot 依赖注入、REST API（运行/事件流/产物/用量/记忆）、持久化与鉴权。

## 启动

```bash
mvn clean test
mvn -pl agentos-server -am spring-boot:run
```

应用默认启用 `LlmAgentPlanner` 和 OpenAI-compatible `ModelClient`。模型参数统一配置在
[`application.yml`](agentos-server/src/main/resources/application.yml) 中，也可以使用环境变量覆盖：

```powershell
$env:AGENTOS_MODEL_API_KEY = "your-api-key"
mvn -pl agentos-server -am spring-boot:run
```

完整的模型配置和兼容模式参见 [`agentos-server`](agentos-server/ReadMe.md)。

运行一次 Agent：

```bash
curl -X POST http://localhost:8080/api/agents/runs \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"session-1","input":"hello agentos"}'
```

实时查看规划、工具、Observation 和 Decision：

```bash
curl -N -X POST http://localhost:8080/api/agents/runs/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"sessionId":"session-1","input":"hello agentos"}'
```

控制台默认使用与浏览器连接解耦的后台运行接口。创建后会返回 `runId`，页面刷新时可以查询快照，
再从最后处理的事件序号继续补播和订阅：

```bash
curl -X POST http://localhost:8080/api/agent-runs \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"session-1","input":"hello agentos"}'

curl http://localhost:8080/api/agent-runs/{runId}
curl -N "http://localhost:8080/api/agent-runs/{runId}/events?after=0"
curl -X POST http://localhost:8080/api/agent-runs/{runId}/cancel
```

事件使用单调递增的 `sequence` 作为 SSE ID。断开或刷新页面只会移除订阅，不会取消后台任务；
`cancel` 接口触发协作式取消，运行在下一个检查点进入 `CANCELLED` 终态。

查询会话状态：

```bash
curl http://localhost:8080/api/agents/session-1/state
```

下载运行产物（工具写入的报告、文档等自动登记为会话产物）：

```bash
curl "http://localhost:8080/api/artifacts?sessionId=session-1"
curl -OJ http://localhost:8080/api/artifacts/{artifactId}
```

查询模型 token 用量：

```bash
curl http://localhost:8080/api/usage/session-1
```

启动独立 Vue 3 控制台：

```bash
cd agentos-console
npm install
npm run dev
```

浏览器访问 `http://localhost:5173`，Vite 会将 `/api` 请求代理到本地 `agentos-server`。

服务默认使用本地版本化文件保存记忆，也可以配置为 JVM 内存或 JDBC/SQLite。单实例持久化部署
建议使用 SQLite；当前向量仍在召回时计算，尚未接入 `sqlite-vec` 或独立向量数据库。详细 API、
作用域、恢复边界和配置见 [`agentos-memory`](agentos-memory/ReadMe.md)。

运行态（领域事件、审批 Checkpoint、断点续跑状态、用量账本）默认保存在进程内存中；
设置 `AGENTOS_PERSISTENCE_MODE=sqlite` 后写入单一 SQLite 文件，进程重启后等待审批的任务
可恢复执行。REST 接口默认不鉴权，配置 `AGENTOS_API_KEY` 后要求 `X-API-Key` 请求头。

## 项目文档

- [架构说明](docs/ARCHITECTURE.md)
- [功能清单](docs/FEATURES.md)
- [功能摘要](docs/FEATURES_SUMMARY.md)
- [分层记忆系统面试项目总结](docs/AGENT_MEMORY_INTERVIEW.md)


## 工具规划

| 外部工具 | 本地工具 |
| --- | --- |
| 天气、股票、搜索、地图、邮件、日历、数据库 | 文件、Shell、Git、代码执行、浏览器 |

当前已实现：文件读写、Shell、Git 提交、网页抓取、搜索、天气、日期。
