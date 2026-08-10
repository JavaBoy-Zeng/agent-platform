package com.github.agentos.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** L1 中可独立检索、去重和演进的一条原子记忆。 */
public record AtomicMemory(
        String id,
        MemoryScope scope,
        MemoryType type,
        String content,
        double confidence,
        int priority,
        int version,
        String sourceTurnId,
        Instant createdAt,
        Instant updatedAt) {

    public AtomicMemory {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content must not be blank");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (priority < 0 || priority > 10) throw new IllegalArgumentException("priority must be between 0 and 10");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        sourceTurnId = Objects.requireNonNull(sourceTurnId, "sourceTurnId must not be null");
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static AtomicMemory create(
            MemoryScope scope,
            MemoryType type,
            String content,
            double confidence,
            int priority,
            String sourceTurnId) {
        Instant now = Instant.now();
        return new AtomicMemory(
                UUID.randomUUID().toString(), scope, type, content.trim(), confidence,
                priority, 1, sourceTurnId, now, now);
    }

    /** 合并新的表达并递增版本。 */
    public AtomicMemory revise(String revisedContent, double revisedConfidence, int revisedPriority, String turnId) {
        return new AtomicMemory(
                id, scope, type, revisedContent.trim(), revisedConfidence, revisedPriority,
                version + 1, turnId, createdAt, Instant.now());
    }
}
