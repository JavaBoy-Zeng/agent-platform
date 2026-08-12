package com.github.agentos.kernel;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 不额外调用模型的首版确定性执行策略。 */
public final class RuleBasedExecutionPolicy implements ExecutionPolicy {

    private static final Set<String> PLAN_MARKERS = Set.of(
            "复杂", "多步骤", "计划", "改造", "实现", "编码", "修改", "重构", "部署",
            "complex", "multi-step", "plan", "implement", "code", "modify", "refactor",
            "deploy", "project");
    private static final Set<String> REACT_MARKERS = Set.of(
            "读取", "查看文件", "查询天气", "调用api", "调用 api", "单个工具",
            "read file", "inspect file", "weather", "call api", "single tool", "lookup");

    /** 优先尊重显式属性，再按风险、复杂度和工具规模选择模式。 */
    @Override
    public ExecutionMode select(AgentRequest request, AgentExecutionContext context) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(context, "context must not be null");
        Object explicit = request.attributes().get("executionMode");
        if (explicit != null) {
            try {
                return ExecutionMode.valueOf(String.valueOf(explicit).trim()
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "unknown executionMode: " + explicit, exception);
            }
        }
        if (booleanAttribute(request, "risky")
                || booleanAttribute(request, "complex")
                || integerAttribute(request, "estimatedToolCalls") > 2
                || containsAny(request.objective(), PLAN_MARKERS)) {
            return ExecutionMode.PLAN;
        }
        if (booleanAttribute(request, "requiresTool")
                || integerAttribute(request, "estimatedToolCalls") > 0
                || containsAny(request.objective(), REACT_MARKERS)) {
            return ExecutionMode.REACT;
        }
        return ExecutionMode.DIRECT;
    }

    private static boolean booleanAttribute(AgentRequest request, String key) {
        Object value = request.attributes().get(key);
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(value));
    }

    private static int integerAttribute(AgentRequest request, String key) {
        Object value = request.attributes().get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static boolean containsAny(String objective, Set<String> markers) {
        String normalized = objective.toLowerCase(Locale.ROOT);
        return markers.stream().anyMatch(normalized::contains);
    }
}
