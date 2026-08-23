package com.github.agentos.memory;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 已成功完成、可以进入 L0 的完整对话轮次。
 *
 * @param id 轮次唯一标识，用于幂等回流
 * @param businessKey actor 维度的稳定业务幂等键
 * @param scope 记忆隔离作用域
 * @param userInput 本轮原始用户输入
 * @param assistantOutput Agent 最终输出
 * @param toolOutputs 本轮工具执行摘要
 * @param completedAt 完成时间
 */
public record CompletedTurn(
        String id,
        String businessKey,
        MemoryScope scope,
        String userInput,
        String assistantOutput,
        List<String> toolOutputs,
        Instant completedAt) {

    public CompletedTurn {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        businessKey = requireText(businessKey, "businessKey");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        userInput = Objects.requireNonNull(userInput, "userInput must not be null");
        assistantOutput = Objects.requireNonNull(assistantOutput, "assistantOutput must not be null");
        toolOutputs = List.copyOf(Objects.requireNonNull(toolOutputs, "toolOutputs must not be null"));
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    /** 兼容旧调用方：旧轮次以自身 ID 作为业务幂等键。 */
    public CompletedTurn(
            String id,
            MemoryScope scope,
            String userInput,
            String assistantOutput,
            List<String> toolOutputs,
            Instant completedAt) {
        this(id, id, scope, userInput, assistantOutput, toolOutputs, completedAt);
    }

    /** 创建一个带随机幂等标识的成功轮次。 */
    public static CompletedTurn success(
            MemoryScope scope,
            String userInput,
            String assistantOutput,
            List<String> toolOutputs) {
        String id = UUID.randomUUID().toString();
        return new CompletedTurn(
                id,
                id,
                scope,
                userInput,
                assistantOutput,
                toolOutputs,
                Instant.now());
    }

    /**
     * 使用稳定业务键创建成功轮次；同一 actor 与业务键始终得到同一个 L0 标识。
     */
    public static CompletedTurn success(
            MemoryScope scope,
            String businessKey,
            String userInput,
            String assistantOutput,
            List<String> toolOutputs) {
        Objects.requireNonNull(scope, "scope must not be null");
        String normalizedKey = requireText(businessKey, "businessKey");
        String material = scope.teamId() + '\u0000' + scope.userId() + '\u0000'
                + scope.agentId() + '\u0000' + normalizedKey;
        String stableId = UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
        return new CompletedTurn(
                stableId, normalizedKey, scope, userInput, assistantOutput, toolOutputs, Instant.now());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
