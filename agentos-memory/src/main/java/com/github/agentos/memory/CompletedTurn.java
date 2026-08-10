package com.github.agentos.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 已成功完成、可以进入 L0 的完整对话轮次。
 *
 * @param id 轮次唯一标识，用于幂等回流
 * @param scope 记忆隔离作用域
 * @param userInput 本轮原始用户输入
 * @param assistantOutput Agent 最终输出
 * @param toolOutputs 本轮工具执行摘要
 * @param completedAt 完成时间
 */
public record CompletedTurn(
        String id,
        MemoryScope scope,
        String userInput,
        String assistantOutput,
        List<String> toolOutputs,
        Instant completedAt) {

    public CompletedTurn {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        scope = Objects.requireNonNull(scope, "scope must not be null");
        userInput = Objects.requireNonNull(userInput, "userInput must not be null");
        assistantOutput = Objects.requireNonNull(assistantOutput, "assistantOutput must not be null");
        toolOutputs = List.copyOf(Objects.requireNonNull(toolOutputs, "toolOutputs must not be null"));
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    /** 创建一个带随机幂等标识的成功轮次。 */
    public static CompletedTurn success(
            MemoryScope scope,
            String userInput,
            String assistantOutput,
            List<String> toolOutputs) {
        return new CompletedTurn(
                UUID.randomUUID().toString(),
                scope,
                userInput,
                assistantOutput,
                toolOutputs,
                Instant.now());
    }
}
