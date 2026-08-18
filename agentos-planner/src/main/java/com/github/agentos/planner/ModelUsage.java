package com.github.agentos.planner;

/**
 * 一次模型调用的 token 用量。
 *
 * @param model 实际调用的模型标识
 * @param promptTokens 输入侧 token 数
 * @param completionTokens 输出侧 token 数
 */
public record ModelUsage(String model, long promptTokens, long completionTokens) {

    /** 创建并校验用量。 */
    public ModelUsage {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    /** 输入与输出合计。 */
    public long totalTokens() {
        return promptTokens + completionTokens;
    }
}
