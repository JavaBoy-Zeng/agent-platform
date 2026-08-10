# agentos-server

`agentos-server` 是 AgentOS 的 Spring Boot 启动与适配层，负责组装所有模块、提供 HTTP API，并生成可独立运行的应用程序。

## 主要职责

- 启动 Spring Boot Web 应用。
- 将规划器、工具、记忆、审批服务、主 Agent 和运行时装配为 Bean。
- 提供创建 Agent 运行和查询会话状态的 REST API。
- 将请求参数错误转换为标准 HTTP Problem Detail 响应。
- 承载跨模块集成测试和可执行 JAR 打包。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `AgentOsApplication` | 标注 `@SpringBootApplication` 的应用入口。 |
| `AgentOsConfiguration` | 装配工具、记忆、审批、计划执行器、主 Agent 和运行时。 |
| `LlmPlannerConfiguration` | 在非 `demo` 环境装配 `LlmTaskPlanner`，并要求提供 `ModelClient`。 |
| `DemoPlannerConfiguration` | 只在 `demo` Profile 下装配 `DemoTaskPlanner`。 |
| `AgentController` | 暴露 Agent 运行和状态查询 API。 |
| `AgentExceptionHandler` | 将非法参数异常转换为 HTTP 400。 |
| `AgentRuntimeIntegrationTest` | 验证规划、工具执行、状态更新和记忆写入的完整链路。 |

## 默认装配

```text
AgentRuntime
    └── MainAgent
        ├── TaskPlanner
        │   ├── 非 demo：LlmTaskPlanner ──► ModelClient
        │   └── demo：DemoTaskPlanner
        ├── PlanExecutor
        │   ├── ToolRegistry ──► EchoTool
        │   ├── ToolExecutor
        │   ├── RiskPolicy
        │   └── ApprovalService
        └── MemoryService
            ├── ShortMemory
            └── LongMemory
```

## HTTP API

### 创建一次运行

```http
POST /api/agents/runs
Content-Type: application/json

{
  "agentId": "main-agent",
  "sessionId": "demo-session",
  "input": "hello agentos",
  "attributes": {}
}
```

`agentId` 和 `sessionId` 可以省略，服务会分别使用默认 Agent ID 和自动生成的会话 ID。

### 查询会话状态

```http
GET /api/agents/{sessionId}/state
```

会话不存在时返回 `404 Not Found`。

## 构建与启动

从项目根目录执行：

```bash
mvn test
mvn -pl agentos-server -am package
java -jar agentos-server/target/agentos-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo
```

`demo` Profile 仅用于本地体验和测试，它使用固定的单步骤回显计划。非 `demo` 环境启用
`LlmTaskPlanner`，必须提供一个具体的 `ModelClient` Bean，否则应用会因为缺少模型适配器而拒绝启动。

## 扩展建议

- 实现 `ModelClient`，接入支持结构化输出的具体大模型服务。
- 注册新的 `AgentTool` Bean，工具会被自动加入注册表。
- 用真实审批渠道替换默认拒绝型 `ApprovalService`。
- 用持久化存储替换内存型长期记忆。

该模块是唯一需要感知 Spring 的模块，领域逻辑应优先保留在其他模块中。
