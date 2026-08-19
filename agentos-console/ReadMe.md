# agentos-console

`agentos-console` 是 AgentOS 的独立前端模块，使用 Vue 3 和 Vite 构建。它不参与 Maven Reactor，也不打包进 Spring Boot JAR，而是通过 HTTP API 与 `agentos-server` 通信。

## 主要职责

- 创建和切换 Agent 会话。
- 通过 12 个管理 Tab 统一查看 Agents、Runs、Sessions、Tools、MCP、Skills、Memory、Plans、Traces、Artifacts、Approvals 和 Models。
- 从运行时只读目录展示真实工具、技能、MCP、模型与执行预算配置。
- 查询分层记忆、链路追踪、模型用量和会话产物，并支持审批处理、产物下载与删除。
- 向 `POST /api/agent-runs` 创建后台任务，并通过 GET SSE 按事件游标持续订阅。
- 页面刷新后按 `runId` 查询快照、补播缺失事件并恢复实时展示。
- 实时展示 Plan、Tool、Observation、Decision、最终输出和失败信息。
- 可视化 MainAgent、Planner、Tool、Observation、Decision 执行管线。
- 将最近 20 个会话及消息保存在浏览器 `localStorage` 中。
- 展示当前 HITL 风险门禁策略。

## 技术栈

- Vue 3 Composition API
- JavaScript
- Vite
- 原生 Fetch API
- 原生 CSS 响应式布局

## 目录结构

```text
agentos-console
├── src
│   ├── components
│   │   ├── SystemHeader.vue
│   │   ├── AppSidebar.vue
│   │   ├── SessionRail.vue
│   │   ├── CommandDeck.vue
│   │   ├── TranscriptPanel.vue
│   │   └── TelemetryRail.vue
│   ├── composables
│   │   └── useAgentConsole.js
│   ├── services
│   │   ├── agentApi.js
│   │   └── consoleApi.js
│   ├── views
│   │   ├── ChatView.vue
│   │   └── ManagementView.vue
│   ├── App.vue
│   ├── router.js
│   ├── main.js
│   └── styles.css
├── index.html
├── package.json
└── vite.config.js
```

## 本地开发

先启动后端：

```bash
mvn -pl agentos-server -am spring-boot:run
```

再启动前端：

```bash
cd agentos-console
npm install
npm run dev
```

访问 `http://localhost:5173`。开发服务器会把 `/api` 请求代理到 `http://localhost:8080`。

## 生产构建

```bash
npm run build
```

构建产物位于 `agentos-console/dist`。部署时需要让 Web Server 将 `/api` 反向代理到 `agentos-server`，并将其他路径指向前端静态资源。

## 数据边界

浏览器中的会话消息只用于控制台展示，不等同于后端 `MemoryService`。运行中的 `runId` 和最后消费的事件序号会随会话一并保存，因此刷新页面不会取消任务；控制台会补播缺失事件并继续订阅。切换浏览器、清理站点数据或重启后端仍会丢失相应的本地或进程内恢复信息。
