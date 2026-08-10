# agentos-tool

`agentos-tool` 定义 Agent 可调用能力的统一协议，并负责工具注册、查找和安全执行。搜索、数据库查询、文件操作或第三方 API 都可以作为 `AgentTool` 接入。

## 主要职责

- 定义工具名称、说明、风险级别、参数结构和执行方法。
- 使用统一模型描述工具参数和执行结果。
- 按名称注册、查找和枚举工具。
- 捕获工具异常，将其转换为标准失败结果。

## 核心类型

| 类型 | 作用 |
| --- | --- |
| `AgentTool` | 工具扩展接口，同时声明 `LOW`、`MEDIUM` 或 `HIGH` 风险等级。 |
| `ToolParameter` | 参数名称、JSON 基础类型、说明和必填标记。 |
| `ToolDefinition` | 面向模型的工具名称、说明、风险和参数结构快照。 |
| `ToolCall` | 一次工具调用，包含工具名称和参数 Map。 |
| `ToolResult` | 标准执行结果，包含成功标记、输出和错误。 |
| `ToolRegistry` | 线程安全的工具注册表，同时向规划器提供稳定排序的工具定义。 |
| `ToolExecutor` | 查找并执行工具，把异常归一化为 `ToolResult.failure`。 |
| `EchoTool` | 内置低风险示例工具，用于验证完整调用链。 |

## 新增工具

```java
public final class SearchTool implements AgentTool {
    @Override
    public String name() {
        return "search";
    }

    @Override
    public String description() {
        return "Searches the knowledge base";
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(new ToolParameter(
                "query",
                ToolParameter.ValueType.STRING,
                "搜索关键词",
                true));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        return ToolResult.success("search result");
    }
}
```

在 Spring Boot 服务中将实现声明为 Bean 后，`ToolRegistry` 会自动收集它。
`ToolRegistry.definitions()` 会把工具说明和参数结构提供给 `LlmTaskPlanner`，同一份定义也用于
执行前参数校验。

## 安全边界

- 工具只声明自身风险等级，不决定是否批准执行。
- 是否需要人工审批由 `agentos-hitl` 的 `RiskPolicy` 判断。
- `ToolExecutor` 负责执行和异常归一化，不绕过审批直接制定安全策略。

模块构建依赖 `agentos-kernel`，工具协议本身保持与具体 Agent 实现解耦。
