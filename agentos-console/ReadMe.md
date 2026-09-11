# agentos-console

`agentos-console` 是 AgentOS 的独立前端模块，使用 Vue 3 和 Vite 构建。它不参与 Maven Reactor，也不打包进 Spring Boot JAR，而是通过 HTTP API 与 `agentos-server` 通信。

## 主要职责

- 创建和切换 Agent 会话。
- 通过 13 个管理 Tab 统一查看 Agents、Runs、Sessions、Tools、MCP、Skills、Memory、Plans、Traces、Artifacts、Approvals、Models 和 Evals。
- 从运行时只读目录展示真实 Agent 拓扑、工具、技能、MCP、模型与执行预算配置。
- 从 `AgentRegistry` 读取 Agent 形态（planner / supervisor / specialist / workflow / specialist 配置化）与工具化状态，点击可查看子 Agent 与声明工具。
- 从服务端读取运行台账（`GET /api/agent-runs`）与会话列表（`GET /api/sessions`），不再以浏览器本地档案推断服务端状态。
- 以后端领域事件（`GET /api/events`）渲染计划与执行轨迹时间线，替代解析前端展示文本。
- 提交工具轨迹评估（`POST /api/evaluations/{invocationId}`）：声明期望工具序列、禁用工具、调用预算与回答关键词，逐项查看检查结论。
- 查询分层记忆、链路追踪、模型用量和会话产物，并支持审批处理、产物下载与删除。
- 向 `POST /api/agent-runs` 创建后台任务，并通过 GET SSE 按事件游标持续订阅。
- 页面刷新后按 `runId` 查询快照、补播缺失事件并恢复实时展示。
- 实时展示 Plan、Tool、Observation、Decision、最终输出和失败信息。
- 可视化 PlanExecuteAgent、Planner、Tool、Observation、Decision 执行管线。
- 从服务端分页加载会话；浏览器仅缓存最近 20 个会话用于快速恢复和断网兜底。
- 本地缓存缺失时，从服务端领域事件恢复完整的用户/助手对话轮次。
- 支持简体中文与英文即时切换；语言偏好保存在 `agentos.console.locale.v1`，并同步页面 `lang` 属性与日期格式。
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

Runs、Sessions、Plans 和 Evals 四个面板的数据来自服务端而非浏览器：Runs 读 `AgentRunCoordinator` 的进程内台账，Sessions 读 `SessionService`，Plans 与 Evals 读 `AgentEventStore`。因此 `memory` 持久化模式下后端重启会清空这些面板；`sqlite` 模式下会话与事件仍可查询，但后台运行台账始终是进程内状态。
