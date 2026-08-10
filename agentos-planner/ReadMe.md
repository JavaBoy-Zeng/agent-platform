# agentos-planner

`agentos-planner` 负责把用户目标转换为可执行计划，并按照计划顺序调度工具。它连接“理解任务”和“执行能力”两个阶段。

## 主要职责

- 定义规划器协议，隔离具体的计划生成方式。
- 收集用户输入、Agent 上下文、短期记忆、长期事实和可用工具定义。
- 通过模型客户端获取结构化计划，并转换为领域计划。
- 在执行前校验工具、参数结构和最大步骤数。
- 表示计划、计划步骤和每一步的工具调用。
- 按顺序执行步骤，并在失败或审批拒绝时停止后续步骤。
- 汇总每一步的状态、输出和错误。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `TaskPlanner` | 根据 `AgentContext` 创建 `Plan` 的函数式接口。 |
| `LlmTaskPlanner` | 收集规划数据、调用模型、转换响应并校验计划的生产规划器。 |
| `ModelClient` | 与模型厂商无关的结构化计划生成协议。 |
| `PlanningRequest` | 发送给模型的输入、上下文、记忆、工具定义和步骤上限。 |
| `ModelPlan` | 模型适配器解析后的结构化传输模型，不能直接执行。 |
| `PlanValidator` | 校验工具存在性、参数必填项、参数类型和步骤数上限。 |
| `PlanValidationException` | 汇总模型计划中的全部校验问题。 |
| `DemoTaskPlanner` | 仅用于 `demo` Profile 的单步骤回显规划器。 |
| `Plan` | 不可变计划模型，包含目标和有序步骤，并校验步骤 ID 唯一性。 |
| `PlanExecutor` | 执行计划，串联工具查找、风险判断、人工审批和工具调用。 |

## LLM 规划流程

```text
AgentContext + MemoryService + ToolRegistry.definitions()
    │
    ▼
PlanningRequest
    │ ModelClient.generatePlan(...)
    ▼
ModelPlan（模型结构化响应）
    │ LlmTaskPlanner 转换
    ▼
Plan + PlanValidator
    │
    ▼
PlanExecutor
    ├── ToolRegistry：查找工具
    ├── RiskPolicy：判断是否需要审批
    ├── ApprovalService：请求人工决策
    └── ToolExecutor：执行工具
```

`ModelClient` 的具体适配器负责提示词、鉴权、模型 API 调用和厂商响应解析。
`LlmTaskPlanner` 不直接依赖任何厂商 SDK，只接收统一的 `ModelPlan`。

计划只有在满足以下条件后才能交给 `PlanExecutor`：

- 步骤数没有超过 `PlanValidator` 配置的上限，默认是 10。
- 每一步引用的工具已经注册。
- 不缺少工具声明的必填参数。
- 不包含未声明参数，并且参数值符合声明的数据类型。

每个步骤的结果状态为：

- `COMPLETED`：工具执行成功。
- `FAILED`：工具不存在、执行异常或返回失败。
- `REJECTED`：风险操作未得到人工批准。

任何步骤失败或被拒绝后，当前执行器都会立即停止后续步骤。

## 模块依赖

- `agentos-kernel`：读取 Agent 上下文。
- `agentos-tool`：描述和执行工具调用。
- `agentos-hitl`：执行风险判断与人工审批。
- `agentos-memory`：读取短期记忆和长期事实。

## 扩展方式

接入具体模型时实现 `ModelClient` 并在 Spring 容器中注册即可。非 `demo` 环境会自动将其注入
`LlmTaskPlanner`；`PlanExecutor` 不需要感知模型厂商。
