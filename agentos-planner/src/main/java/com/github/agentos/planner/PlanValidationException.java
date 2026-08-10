package com.github.agentos.planner;

import java.util.List;
import java.util.Objects;

/**
 * 表示模型生成的计划没有通过执行前校验。
 */
public final class PlanValidationException extends IllegalArgumentException {

    /** 本次计划校验发现的全部问题。 */
    private final List<String> violations;

    /**
     * 使用全部校验问题创建异常。
     *
     * @param violations 校验问题列表
     * @throws NullPointerException 当问题列表为 {@code null} 时抛出
     * @throws IllegalArgumentException 当问题列表为空时抛出
     */
    public PlanValidationException(List<String> violations) {
        super(buildMessage(violations));
        this.violations = List.copyOf(violations);
    }

    /**
     * 获取全部校验问题。
     *
     * @return 只读问题列表
     */
    public List<String> violations() {
        return violations;
    }

    private static String buildMessage(List<String> violations) {
        Objects.requireNonNull(violations, "violations must not be null");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        return "invalid model-generated plan: " + String.join("; ", violations);
    }
}
