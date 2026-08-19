package com.github.agentos.kernel;

/**
 * 一次模型调用的 token 用量。
 *
 * <p>由模型客户端在调用成功后产出，经 {@link AgentPlugin#onModelUsage} 或
 * 模型客户端监听器汇入记账、日志或事件流；规划路径与直答路径共用。</p>
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
