package com.github.agentos.memory;

import java.util.Objects;
import java.util.Set;

/** 规划前针对当前输入发起的记忆检索请求。 */
public record MemoryQuery(
        MemoryScope scope,
        String text,
        int limit,
        Set<MemoryType> types) {

    public MemoryQuery {
        scope = Objects.requireNonNull(scope, "scope must not be null");
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text must not be blank");
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        types = Set.copyOf(Objects.requireNonNull(types, "types must not be null"));
    }

    public static MemoryQuery planning(MemoryScope scope, String text, int limit) {
        return new MemoryQuery(scope, text, limit, Set.of());
    }
}
