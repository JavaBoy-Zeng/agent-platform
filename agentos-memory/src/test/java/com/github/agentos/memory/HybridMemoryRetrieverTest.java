package com.github.agentos.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HybridMemoryRetrieverTest {

    @Test
    void ranksSemanticallyRelevantMemoryFirstAndHonorsTypeFilter() {
        MemoryScope scope = new MemoryScope("team", "user", "agent", "session", "task");
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.upsertAtomic(memory("java", scope, MemoryType.DECISION, "项目采用 Java 虚拟线程"));
        store.upsertAtomic(memory("food", scope, MemoryType.PREFERENCE, "用户喜欢意大利面"));
        store.upsertAtomic(memory("travel", scope, MemoryType.FACT, "上海到东京需要护照"));

        Map<String, double[]> vectors = Map.of(
                "如何处理 Java 并发？", new double[]{1, 0, 0},
                "项目采用 Java 虚拟线程", new double[]{0.99, 0.01, 0},
                "用户喜欢意大利面", new double[]{0, 1, 0},
                "上海到东京需要护照", new double[]{0, 0, 1});
        HybridMemoryRetriever retriever = new HybridMemoryRetriever(store, text -> vectors.get(text));

        assertThat(retriever.search(new MemoryQuery(
                scope, "如何处理 Java 并发？", 2, java.util.Set.of())))
                .extracting(hit -> hit.memory().id())
                .containsExactly("java");
        assertThat(retriever.search(new MemoryQuery(
                scope, "如何处理 Java 并发？", 2, java.util.Set.of(MemoryType.PREFERENCE))))
                .isEmpty();
    }

    @Test
    void rejectsDifferentEmbeddingDimensions() {
        MemoryScope scope = new MemoryScope("team", "user", "agent", "session", "");
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.upsertAtomic(memory("memory", scope, MemoryType.FACT, "known fact"));
        HybridMemoryRetriever retriever = new HybridMemoryRetriever(
                store, text -> text.equals("query") ? new double[]{1, 0} : new double[]{1});

        assertThatThrownBy(() -> retriever.search(MemoryQuery.planning(scope, "query", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions");
    }

    private static AtomicMemory memory(String id, MemoryScope scope, MemoryType type, String content) {
        Instant now = Instant.now();
        return new AtomicMemory(id, scope, type, content, 0.9, 8, 1, "turn", now, now);
    }
}
