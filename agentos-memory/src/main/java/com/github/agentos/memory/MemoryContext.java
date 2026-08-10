package com.github.agentos.memory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 经过检索、隔离和预算裁剪后提供给规划器的记忆上下文。 */
public record MemoryContext(
        List<CompletedTurn> recentTurns,
        List<MemorySearchHit> atomicMemories,
        List<ScenarioMemory> scenarios,
        ProfileMemory profile,
        String formattedContext,
        boolean degraded) {

    public MemoryContext {
        recentTurns = List.copyOf(Objects.requireNonNull(recentTurns, "recentTurns must not be null"));
        atomicMemories = List.copyOf(Objects.requireNonNull(atomicMemories, "atomicMemories must not be null"));
        scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios must not be null"));
        formattedContext = Objects.requireNonNull(formattedContext, "formattedContext must not be null");
    }

    public Optional<ProfileMemory> optionalProfile() {
        return Optional.ofNullable(profile);
    }

    public static MemoryContext empty(boolean degraded) {
        return new MemoryContext(List.of(), List.of(), List.of(), null, "", degraded);
    }
}
