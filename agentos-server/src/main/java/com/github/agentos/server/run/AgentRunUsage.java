package com.github.agentos.server.run;

/** 一次 Run 的聚合模型用量。 */
public record AgentRunUsage(
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        long totalTokens,
        long modelCalls) {

    public static final AgentRunUsage ZERO = new AgentRunUsage(0, 0, 0, 0, 0);

    public AgentRunUsage {
        if (inputTokens < 0 || outputTokens < 0 || cachedTokens < 0
                || totalTokens < 0 || modelCalls < 0) {
            throw new IllegalArgumentException("usage values must not be negative");
        }
    }

    public AgentRunUsage plus(long input, long output, long cached) {
        return new AgentRunUsage(
                inputTokens + Math.max(0, input),
                outputTokens + Math.max(0, output),
                cachedTokens + Math.max(0, cached),
                totalTokens + Math.max(0, input) + Math.max(0, output),
                modelCalls + 1);
    }
}
