package com.github.agentos.memory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** L2 项目或任务场景摘要。 */
public record ScenarioMemory(
        String id,
        MemoryScope scope,
        String name,
        String content,
        int version,
        Instant updatedAt) {

    public ScenarioMemory {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content must not be blank");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static ScenarioMemory create(MemoryScope scope, String name, String content) {
        return new ScenarioMemory(UUID.randomUUID().toString(), scope, name, content, 1, Instant.now());
    }

    public ScenarioMemory revise(String revisedContent) {
        return new ScenarioMemory(id, scope, name, revisedContent, version + 1, Instant.now());
    }
}
