# AgentOS 路由流程说明

> 文档基线：`feature/react-loop` 分支，2026-09-08 当前代码
>
> 目标读者：首次接触 `agentos-agent` 路由层、想搞清楚"一个请求进来后到底走了哪些 Agent"的研发与运维
>
> 说明：本文以代码为准，所有类名、方法名、配置项均直接引用实现

## 1. 一张图总览

AgentOS 的路由分 **三层**，从外到内依次是：

| 层 | 角色 | 关键类型 | 决策成本 |
| --- | --- | --- | --- |
| 入口路由器 | 在请求进入执行循环前产出路由决策 | `RoutingAgentLoop` | 0（纯转发） |
| 启发式意图分类器 | 零 LLM 的规则过滤 | `HeuristicIntentClassifier` | 0（纯规则） |
| 监督 Agent | 单次 LLM 调用，把任务分给专才 Agent 或 fallback | `SupervisorAgent` | 1 次 LLM |
| 专才 Agent | 单职责执行：搜索 / 代码 / 文档 | `SearchAgent` / `CodeAgent` / `ReportAgent` | 视任务而定 |
| fallback | 复杂任务兜底 | `PlanExecuteAgent` / `ReactAgent`（均参与路由） | 多轮 |

请求走向一句话概括：**入口路由器拿到请求 → 意图识别先过一遍零成本过滤 → 没过就交给 Supervisor 单次 LLM 分类 → Supervisor 把请求派给专才 Agent，或者回退到 PlanExecuteAgent / ReactAgent**。

```
                  ┌──────────────────────────────────────┐
                  │      RoutingAgentLoop (入口)         │
                  └──────────────────────────────────────┘
                                    │
              classifier.classify(request, context)
                                    │
                                    ▼
                  ┌──────────────────────────────────────┐
                  │   HeuristicIntentClassifier (零LLM)  │
                  └──────────────────────────────────────┘
                shortCircuit │  routeTo(simple-qa)  │  routeTo(SystemCatalog)
                            │                     │
                            ▼                     ▼
                       canned answer        SimpleQaAgent
                                                  │
                       fallback（其余情况）──────┘
                                    │
                                    ▼
                  ┌──────────────────────────────────────┐
                  │      SupervisorAgent (1×LLM)         │
                  └──────────────────────────────────────┘
                plan-execute-agent / search-agent / code-agent / report-agent
                低置信度 / 不接受 → fallback（PlanExecuteAgent 或 ReactAgent）
                                    │
            ┌───────────────┬───────┴────────┬──────────────┐
            ▼               ▼                ▼              ▼
        SearchAgent     CodeAgent      ReportAgent    PlanExecuteAgent / ReactAgent
```

## 2. 入口路由器：`RoutingAgentLoop`

源码：`agentos-agent/src/main/java/com/github/agentos/agent/routing/RoutingAgentLoop.java`

`RoutingAgentLoop` 实现 `AgentLoop` 接口，被 `AgentRunner` 直接调用（见 `AgentOsConfiguration#agentRunner`）。它自身不做任何决策，只把请求丢给分类器，然后按分类结果三选一：

```java
IntentClassification classification = classifier.classify(request, context);
if (classification.isShortCircuit()) {        // directAnswer != null
    return shortCircuit(...);                 // 直接返回 canned answer
}
if (classification.hasAgentTarget()) {        // agentId != null
    return dispatch(...);                     // 从 AgentRegistry 取出 Agent 执行
}
return fallback.run(...);                     // 其他全部走 fallback
```

`IntentClassification` 是一个 record，包含六个字段（见 `routing/IntentClassification.java`）：

| 字段 | 含义 |
| --- | --- |
| `intent` | 人类可读标签，例如 `trivial-greeting` / `simple-qa` / `system-introspection` / `agent:researcher` |
| `agentId` | `AgentRegistry` 中已注册的 Agent 标识；`null` 走 fallback |
| `executionMode` | 由分类器指定的执行模式（DIRECT / REACT / PLAN）；`null` 由目标 Agent 自己决定 |
| `confidence` | 置信度，`[0.0, 1.0]` |
| `directAnswer` | 非 `null` 时跳过 LLM 直接返回 |
| `attributes` | 路由决策携带的扩展属性（如 `requiresFileEvidence=true`） |

派发前，`RoutingAgentLoop#dispatch` 还会做两件硬校验：

1. 目标 Agent 必须实现 `AgentLoop` 接口（否则 `IllegalStateException`）；
2. 通过 `context.withAgentId(agentId)` 把目标标识透传给下游执行上下文。

dispatch 与 fallback 都会先经过 `routedRequest(request, classification)`：把分类阶段的 `attributes` 与 `executionMode` 合并进请求的 attributes（请求侧同名属性优先保留，`executionMode` 以路由决策为准）。

## 3. 第一层决策：启发式意图分类器

源码：`agentos-agent/src/main/java/com/github/agentos/agent/routing/HeuristicIntentClassifier.java`

零 LLM 调用，纯字符串匹配。判定顺序固定：

1. **寒暄短路**：目标经 `normalize`（剥首尾空白与 Unicode 标点 + 小写）后命中 `trivialGreetings` / `trivialAcknowledgements` / `trivialThanks` / `trivialFarewells` 白名单，且长度不超过 `agentos.router.short-circuit.max-chars`（默认 16）。命中即返回 `directAnswer` 不再走模型。会话历史存在时，`contextualAnswers`（`yes/no/ok/好/是` 等）不再短路，避免误把上一轮回答当成新会话问候。
2. **文件上下文追问**：若请求 attributes 里 `conversationFileContext=true` 且目标命中 `file-context-follow-up` 信号词（"上面"/"前面"/"这些"/"file"/"document" 等），返回 `intent=file-context-follow-up` 并设置 `requiresFileEvidence=true`，落到 fallback 走 PlanExecuteAgent 并保留文件证据链。
3. **本地运行时目录查询**：`agentos` + 工具/Agent/MCP/技能等目录词命中 → `routeTo(SystemCatalogAgent.ID, "system-introspection")`。带"联网/外部资料"则不命中；带"当前/本系统"等强本地信号即使不含 `agentos` 也命中。`history` 中含"运行在 agentos"等上下文也算命中。
4. **简单问答**：长度不超过 `agentos.router.simple-qa.max-chars`（默认 32）、不命中任何 `taskSignals`（动作动词、实时信息词、文件系统与网络信号等）、且不含文件路径/可读文件扩展名 → `routeTo(simple-qa-agent, "simple-qa")`。
5. **其余** → `fallback("heuristic-fallback")`。

> `taskSignals` 是一个保守黑名单：把简单问答错送进复杂链路只是多花 token；把需要工具的任务错送进直答路径会产生幻觉，因此黑名单偏宽。

## 4. 第二层决策：监督 Agent `SupervisorAgent`

源码：`agentos-agent/src/main/java/com/github/agentos/agent/specialist/SupervisorAgent.java`

当 Heuristic 给的是 fallback（或低置信度不可接受），请求就交给 `SupervisorAgent`。它做一次 LLM 调用，强制模型输出结构化 JSON（`SupervisorRouteDecision`）：

```json
{
  "intent": "research-latest-news",
  "scope": "EXTERNAL_WORLD",
  "requiredCapabilities": ["WEB_RESEARCH"],
  "targetAgent": "search-agent",
  "confidence": 0.92,
  "reason": "用户问的是当前股价，必须联网",
  "clarifyingQuestion": ""
}
```

`scope` 必须是 `LOCAL_RUNTIME` / `LOCAL_WORKSPACE` / `EXTERNAL_WORLD` / `GENERAL` 之一（见 `RouteScope`）；`requiredCapabilities` 必须是 `AgentCapability` 枚举之一；`targetAgent` 候选根据实际装配生成，包括两种编排 Agent、专才及工具组 Agent。

`SupervisorAgent` 内部决策流程：

1. 解析模型输出为 `SupervisorRouteDecision`（剥 ``` 围栏、`Jackson` 反序列化）。
2. 若 `confidence < minConfidence`（默认 `agentos.router.supervisor.min-confidence=0.75`）：有 `clarifyingQuestion` 直接返回澄清问题；否则以 `LOW_CONFIDENCE` 拒绝并 fallback。
3. `targetAgent` 为 `plan-execute-agent` 或 `react-agent` → 派发到对应执行 Agent，名称与实际执行对象一致。
4. 目标未注册 / 未实现 `RoutableAgent` + `AgentLoop` → 以 `UNKNOWN_AGENT` / `AGENT_NOT_ROUTABLE` 拒绝并 fallback。
5. `specialist.accepts(request, decision)` 拒绝 → 以 `AGENT_REJECTED` 拒绝并 fallback。
6. 全部通过 → 派发专才并记录实际执行 Agent；专才内部的每个工具操作都经过 `ToolDispatcher`，无需因权限模式提前回退。

> 重要：Supervisor 的 fallback 由 `SpecialistConfiguration#supervisorAgent` 装配。两种 Agent 始终装配并参与路由；`agentos.agent.loop.mode` 仅决定默认回退对象：缺省或 `react` 为 `ReactAgent`，`plan` 为 `PlanExecuteAgent`。

## 5. 第三层：专才 Agent

内置专才和通用工具组都实现 `RoutableAgent`（即 `Agent` + `capabilities()` + `accepts(...)`），且实现 `AgentLoop`，因此既能被 Supervisor 派发，也能通过 `AgentToolAdapter` 暴露成工具供 PlanExecuteAgent / ReactAgent 内部调用。

| Agent | ID | 能力 | scope 校验 | 职责 |
| --- | --- | --- | --- | --- |
| `SearchAgent` | `search-agent` | `WEB_RESEARCH` | 必须 `EXTERNAL_WORLD` | 搜索候选来源、抓取正文、LLM 整理摘要 |
| `CodeAgent` | `code-agent` | `CODE_WRITE` + `COMMAND_EXECUTION` | 拒 `EXTERNAL_WORLD` 与 `LOCAL_RUNTIME` | LLM 生成代码 → 写临时文件 → 命令执行；最多修复 3 轮 |
| `ReportAgent` | `report-agent` | `DOCUMENT_GENERATION` | 拒 `EXTERNAL_WORLD` 与 `LOCAL_RUNTIME` | LLM 生成 Markdown → 写文件并登记为产物 |

每个专才 Agent 都有自己的 `accepts()`（在 `RoutableAgent.accepts()` 默认实现之上叠加作用域校验），是派发前最后一道防线：

- `SearchAgent.accepts`：scope 必须是 `EXTERNAL_WORLD` 且必须含 `WEB_RESEARCH`，否则拒。
- `CodeAgent.accepts`：scope 不得是 `EXTERNAL_WORLD` / `LOCAL_RUNTIME`，且 `requiredCapabilities` 必须含 `CODE_WRITE` 或 `COMMAND_EXECUTION`。
- `ReportAgent.accepts`：scope 不得是 `EXTERNAL_WORLD` / `LOCAL_RUNTIME`，且必须含 `DOCUMENT_GENERATION`。

### 5.1 编排与工具执行边界

生产环境中，PlanExecuteAgent / ReactAgent 只能调用实现 `AgentDelegationTool` 的 Agent 委派。
`ToolRegistry` 按 Invocation 过滤模型能力清单；`ToolDispatcher` 再次校验，拒绝编排层直接调用
原始工具，即使模型生成了未提供的工具名也不会执行。ReAct 每轮重新解析 Provider，支持启动后注册的能力。

`ToolProviderAgent` 使用原生 function calling 处理完整参数，并按当前 Invocation 的 Provider
限制候选与实际执行。默认提供两个业务组：

- `workspace-agent`：文件读取、搜索、修改、目录、命令、Git；返回文件读取证据供父规划器校验。
- `utility-agent`：日期、天气、运行时与其他集成工具；后注册的 MCP/Skill 适配工具在调用时可见。

已有 SearchAgent 的正文证据校验、CodeAgent 的有限修复、ReportAgent 的命名与产物行为保留。
Skill 本身是操作指南；只有其中可执行能力通过工具适配接入，不能把加载指南当作完成操作。

### 5.2 父子审批与恢复

Agent 委派不代表批准内部工具。`ApprovalToolInterceptor` 在子 Agent 调用实际工具时，根据
该工具和参数判断审批；审批界面展示具体操作，例如 `file_write` 的路径与内容。

`AgentToolAdapter` 为子任务保存独立 Checkpoint，把实际 PendingAction 传给父循环。
父循环保存自己的剩余计划或 ReAct 消息。恢复时校验父子归属与原委派参数，向原子任务移交
一次批准；子任务遇到下一次审批时父循环继续 WAITING。拒绝审批会递归清理父子恢复数据。

`ResumableSpecialist` 保存工作流重放日志：模型生成内容、输出路径、已完成工具调用及结果。
审批恢复时读取这些已完成结果，只执行挂起及之后的操作；参数与保存的调用不一致会拒绝执行。
工作流开发者必须用 `remember` 保存会影响工具参数的非确定性计算，通过 `dispatchTool` 调工具。
此机制保证正常审批恢复不重复已记录的操作，不提供工具副作用与数据库写入之间的分布式事务。
恢复跨进程依赖配置的持久化 CheckpointStore / ContinuationStore；memory 模式仅支持进程内恢复。

## 6. fallback：`PlanExecuteAgent` 与 `ReactAgent`

Supervisor 按任务选择执行 Agent：明确步骤与依赖的任务选择 `plan-execute-agent`；排障、探索和下一步依赖工具观察的任务选择 `react-agent`。分类失败、低置信度或专才拒单时才使用配置的默认 Agent。两者始终实例化：

- `plan-execute-agent`：`PlanExecuteAgent`，Plan-and-Execute 模型，先 `AgentPlanner.createPlan` 生成完整计划，迭代执行步骤、按 `replan` 决定重规划或收口（见 `agent/loop/PlanExecuteAgent.java`）。
- `react-agent`：`ReactAgent`，原生 function calling 循环，每轮把"目标 + 当前观察"交给模型决定下一步（见 `agent/loop/ReactAgent.java`）。

> 两条路径都通过 `AgentToolAdapter` 把 `search-agent` / `code-agent` / `report-agent` 暴露成工具，主循环内部可以"路由"到这些专才 Agent —— 这是与 Supervisor 并存的第二条委托路径。

## 7. 端到端示例

下面五个请求覆盖了所有主流走向。每条都给出命中点与最终执行者。**本节只追踪请求的路由路径与最终交给哪个 Agent，不展示 Agent 实际回复内容**——天气、新闻、代码执行结果、文档产物等具体输出取决于检索源、模型生成与运行时执行，不在本文档讨论范围内。

### 7.1 "你好"

```
Heuristic: normalize("你好") → "你好" ∈ trivialGreetings
        → IntentClassification.shortCircuit("trivial-greeting", "你好！有什么我可以帮你的吗？")
RoutingAgentLoop: isShortCircuit() = true → 直接返回 canned answer
```
结果：不调 LLM，不调工具。

### 7.2 "查一下 2026 年 GitHub 上最热门的 5 个 Python 开源项目"

```
Heuristic: 命中 taskSignals "查一下" → 不命中 simple-qa
        → IntentClassification.fallback("heuristic-fallback")
RoutingAgentLoop: fallback.run(...) → SupervisorAgent
Supervisor: LLM 返回 scope=EXTERNAL_WORLD, targetAgent=search-agent, WEB_RESEARCH, confidence=0.88
        → SearchAgent.accepts 通过 → 派发
SearchAgent: browser_search 找候选 → web_fetch 抓 README/star 数 → LLM 整理摘要 → 完成
```

> 注：天气类查询（"今天北京天气怎么样"）实际可能路由到 `plan-execute-agent` 而非 `search-agent`，因为 `WeatherTool` 已经注册到 `ToolRegistry`，PlanExecuteAgent 可以直接调用 `weather` 工具拿到答案。Supervisor 是单次 LLM 决策，模型可能基于"已有现成工具"选择 `plan-execute-agent` 而非 search-agent，**实际路由会随可用工具与提示词版本变化**，本节示例只展示规则的预期路径。

### 7.3 "写一段 Python 计算斐波那契"

```
Heuristic: 命中 taskSignals "写" → fallback
Supervisor: LLM 返回 scope=LOCAL_WORKSPACE, targetAgent=code-agent, CODE_WRITE + COMMAND_EXECUTION
        → CodeAgent.accepts 通过 → 派发
CodeAgent: LLM 生成代码 → file_write 临时文件 → run_command 执行 → 失败最多修复 3 轮
```

### 7.4 "总结一下 HTTP/2 与 HTTP/3 的区别"

```
Heuristic: 命中 taskSignals "总结" / "区别" → fallback
Supervisor: LLM 判定属于"知识整理类任务"（系统指令明确不允许选 search-agent）
        → targetAgent=plan-execute-agent, scope=GENERAL
RoutingAgentLoop: targetAgent=plan-execute-agent → fallback
PlanExecuteAgent: 生成 Plan-and-Execute 计划，按需调工具，模型知识为主体
```

### 7.5 "agentos 现在支持哪些工具"

```
Heuristic: 命中 CATALOG_OBJECT_SIGNALS("工具") + STRONG_LOCAL_RUNTIME_SIGNALS("现在") + "agentos"
        → routeTo(SystemCatalogAgent.ID, "system-introspection")
RoutingAgentLoop: hasAgentTarget() = true → 从 AgentRegistry 取出 SystemCatalogAgent → 直接派发
SystemCatalogAgent: 读取运行时目录（已注册工具/Agent/Skill/MCP/模型）并直接返回
```

## 8. 关键类型速查

| 类型 | 路径 | 作用 |
| --- | --- | --- |
| `RoutingAgentLoop` | `agent/agent/routing/RoutingAgentLoop.java` | 入口路由器，三选一 |
| `HeuristicIntentClassifier` | `agent/agent/routing/HeuristicIntentClassifier.java` | 零 LLM 启发式分类器 |
| `SupervisorAgent` | `agent/agent/specialist/SupervisorAgent.java` | 单次 LLM 决策 + 派发/回退 |
| `IntentClassifier` | `agent/agent/routing/IntentClassifier.java` | 分类器接口（可替换为 LLM 版） |
| `IntentClassification` | `agent/agent/routing/IntentClassification.java` | 不可变路由决策（shortCircuit / agentId / attributes） |
| `SupervisorRouteDecision` | `agent/agent/routing/SupervisorRouteDecision.java` | LLM 输出的 JSON 决策 |
| `RoutableAgent` | `agent/agent/routing/RoutableAgent.java` | 可被 Supervisor 派发的 Agent 接口 |
| `RouteAcceptance` | `agent/agent/routing/RouteAcceptance.java` | 专才 Agent 接单结果 |
| `RouteScope` | `agent/agent/routing/RouteScope.java` | LOCAL_RUNTIME / LOCAL_WORKSPACE / EXTERNAL_WORLD / GENERAL |
| `AgentCapability` | `agent/agent/routing/AgentCapability.java` | 能力枚举 |
| `AgentRegistry` | `agent/agent/registry/AgentRegistry.java` | Agent 注册中心（按 id 派发） |

## 9. 配置入口与扩展点

### 9.1 Spring 装配位置

| Bean | 文件 |
| --- | --- |
| `RoutingAgentLoop`、`IntentClassifier`、`SimpleQaAgent`、`AgentRegistry` | `agentos-server/.../config/RoutingConfiguration.java` |
| `SearchAgent`、`CodeAgent`、`ReportAgent`、`SupervisorAgent` | `agentos-server/.../config/SpecialistConfiguration.java` |
| `ReactAgent` | `agentos-server/.../config/ReactLoopConfiguration.java` |
| `PlanExecuteAgent` | `agentos-server/.../config/AgentOsConfiguration.java` |
| 工具层 `file_write` / `run_command` / `browser_search` / `web_fetch` | `AgentOsConfiguration` 同一文件 |

`RoutingAgentLoop` 通过 `@ConditionalOnMissingBean` 暴露，允许自定义实现覆盖。

### 9.2 关键配置项

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `agentos.router.short-circuit.max-chars` | 16 | 寒暄短路的最大长度 |
| `agentos.router.short-circuit.{greeting,acknowledgement,thanks,farewell}-message` | 内置中文短语 | 短路回复文案 |
| `agentos.router.simple-qa.max-chars` | 32 | 简单问答分级长度上限；`<=0` 关闭该分级 |
| `agentos.router.supervisor.min-confidence` | 0.75 | Supervisor 置信度阈值 |
| `agentos.agent.loop.mode` | `react` | 默认回退（不限制可路由 Agent）：`plan` → `PlanExecuteAgent`，`react` → `ReactAgent` |
| `agentos.agent.react.{max-model-calls,max-tool-calls,max-context-chars,...}` | 见 `ReactLoopConfiguration` | ReactAgent 运行预算 |
| `agentos.agents.config-file` | 未设置 | YAML 配置驱动 Agent 入口（见 `ConfigAgentConfiguration`） |

### 9.3 扩展点

1. **替换意图分类器**：实现 `IntentClassifier` 并以 `@Bean` 形式暴露即可覆盖默认 `HeuristicIntentClassifier`。新分类器只需返回 `IntentClassification`，剩下路由由 `RoutingAgentLoop` 完成。
2. **新增专才 Agent**：实现 `RoutableAgent` + `AgentLoop`，声明 `capabilities()` 与 `accepts(...)`，交给 Spring 容器后会被自动注册进 `AgentRegistry`，同时通过 `AgentToolAdapter` 暴露给 PlanExecuteAgent / ReactAgent。Supervisor 的候选列表由实际注册映射生成；新增类型仍需补充对应的 `scope` / `requiredCapabilities` 和选择规则。
3. **新增执行 Agent**：实现 `AgentLoop`，在 `SpecialistConfiguration#supervisorAgent` 的执行 Agent 映射中注册，并添加对应的提示词选择规则。
4. **YAML 配置 Agent**：在 `agentos.agents.config-file` 指定的 YAML 文件里按 `AgentDefinition` 写定义，由 `ConfigAgentConfiguration` 在启动后扫描加载并注册进 `AgentRegistry` 与 `ToolRegistry`。这类 Agent 不会被 Supervisor 派发，仅作为 PlanExecuteAgent / ReactAgent 内部的工具。
审批检查点会保存实际选中的执行 Agent；恢复和拒绝审批时均委派给原 Agent，避免重新分类或切换执行模式。

`PlanExecuteAgent`（`plan-execute-agent`）原名 `MainAgent`。旧 `main-agent` 注册表查询与审批检查点保留兼容；历史事件和记忆数据不批量改写。`agentos.agent.loop.mode=plan` 的配置值保持不变。
