package com.github.agentos.tool.api;

import java.util.List;
import java.util.Map;

/**
 * 暴露给 Agent 调用的工具协议。
 *
 * <p>每个工具都必须提供稳定的名称、面向规划器的功能说明以及实际执行逻辑。
 * 工具只声明风险等级，是否需要人工审批由 HITL 模块统一决定。</p>
 */
public interface AgentTool {

    /**
     * 获取工具的唯一名称。
     *
     * @return 用于注册和调用工具的稳定名称
     */
    String name();

    /**
     * 获取工具功能说明。
     *
     * @return 供规划器或使用者理解工具能力的说明
     */
    String description();

    /**
     * 获取工具参数结构。
     *
     * <p>实现类应明确声明所有可接受参数。规划器会把这些信息提供给模型，计划校验器
     * 会拒绝未声明参数、缺失的必填参数和类型不匹配的参数。</p>
     *
     * @return 工具参数定义，默认表示工具不接收参数
     */
    default List<ToolParameter> parameters() {
        return List.of();
    }

    /**
     * 获取工具参数的 JSON Schema 表示。
     *
     * <p>JSON Schema 是面向模型与外部协议（OpenAI、Gemini、Claude、MCP、OpenAPI）
     * 的统一参数交换格式，默认由 {@link #parameters()} 派生；
     * 需要表达嵌套结构或枚举约束的工具可以直接覆盖本方法。</p>
     *
     * @return object 类型的 JSON Schema 映射
     */
    default Map<String, Object> parametersSchema() {
        return JsonSchemas.fromParameters(parameters());
    }

    /**
     * 获取工具的风险等级。
     *
     * @return 风险等级，默认是低风险
     */
    default RiskLevel riskLevel() {
        return RiskLevel.LOW;
    }

    /**
     * 是否允许与同一步骤中的其他工具并行执行。
     *
     * <p>默认关闭，工具实现必须确认没有共享写入或顺序依赖后显式开启。</p>
     */
    default boolean parallelSafe() {
        return false;
    }

    /**
     * 执行一次工具调用。
     *
     * @param context 本次工具调用可访问的运行世界（Invocation、会话状态、预算等）
     * @param call 工具名称及调用参数
     * @return 标准化的工具执行结果
     * @throws Exception 当底层工具执行发生异常时抛出，由工具执行器统一转换
     */
    ToolResult execute(ToolContext context, ToolCall call) throws Exception;

    /**
     * 工具操作的风险等级。
     */
    enum RiskLevel {
        /** 只读或无明显副作用的低风险操作。 */
        LOW,
        /** 可能产生有限副作用、建议人工确认的操作。 */
        MEDIUM,
        /** 可能造成重要数据或外部系统变更的高风险操作。 */
        HIGH
    }
}
