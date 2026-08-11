# agentos-console

`agentos-console` 是 AgentOS 的独立前端模块，使用 Vue 3 和 Vite 构建。它不参与 Maven Reactor，也不打包进 Spring Boot JAR，而是通过 HTTP API 与 `agentos-server` 通信。

## 主要职责

- 创建和切换 Agent 会话。
- 向 `POST /api/agents/runs/stream` 提交任务指令并解析 SSE 响应。
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
│   │   ├── SessionRail.vue
│   │   ├── CommandDeck.vue
│   │   ├── TranscriptPanel.vue
│   │   └── TelemetryRail.vue
│   ├── composables
│   │   └── useAgentConsole.js
│   ├── services
│   │   └── agentApi.js
│   ├── App.vue
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

浏览器中的会话消息只用于控制台展示，不等同于后端 `MemoryService`。刷新页面后本地会话仍然存在，但切换浏览器或清理站点数据会丢失。后端运行状态以 AgentOS API 返回结果为准。
