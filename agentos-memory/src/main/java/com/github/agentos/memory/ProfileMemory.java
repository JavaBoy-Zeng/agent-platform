package com.github.agentos.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** L3 中面向用户和 Agent 的稳定长期画像。 */
public record ProfileMemory(
        String id,
        MemoryScope scope,
        String content,
        int version,
        Instant updatedAt) {

    public ProfileMemory {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content must not be blank");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static ProfileMemory create(MemoryScope scope, String content) {
        return new ProfileMemory(UUID.randomUUID().toString(), scope, content, 1, Instant.now());
    }

    public ProfileMemory revise(String revisedContent) {
        return new ProfileMemory(id, scope, revisedContent, version + 1, Instant.now());
    }
}
