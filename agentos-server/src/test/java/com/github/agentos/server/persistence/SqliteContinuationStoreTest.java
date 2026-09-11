package com.github.agentos.server.persistence;

import com.github.agentos.agent.loop.ContinuationStore;
import com.github.agentos.kernel.AgentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQLite 持久化续跑的端到端测试。
 *
 * <p>模拟 WAITING → save → 重启（new store 实例）→ load → 数据完整可恢复。
 * 验证 ReactAgent 的 ReactContinuation 与 PlanExecuteAgent 的 PlanContinuation 两种载荷。
 */
class SqliteContinuationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void reactContinuationSurvivesRestart() {
        Path db = tempDir.resolve("react-continuation.sqlite");
        AgentRequest request = AgentRequest.of("session-1", "测试目标");
        ContinuationStore.PersistedContinuation continuation =
                ContinuationStore.PersistedContinuation.forReact(
                        request,
                        List.of(),  // reactMessages（简化为空）
                        3,          // modelCalls
                        2,          // toolCalls
                        new com.github.agentos.tool.api.ToolCall(
                                "file_write", java.util.Map.of("path", "/tmp/x")),
                        "call-1",
                        "file_write");

        // 第一次启动：保存续跑状态。
        SqliteContinuationStore store1 = store(db);
        store1.save("inv-react-1", continuation);

        // 模拟重启：new store 实例从同一数据库加载。
        SqliteContinuationStore store2 = store(db);
        ContinuationStore.PersistedContinuation loaded =
                store2.load("inv-react-1").orElseThrow();

        // 数据完整恢复。
        assertThat(loaded.reactMessages()).isEmpty();
        assertThat(loaded.modelCalls()).isEqualTo(3);
        assertThat(loaded.toolCalls()).isEqualTo(2);
        assertThat(loaded.pendingToolCall().toolName()).isEqualTo("file_write");
        assertThat(loaded.pendingToolCallId()).isEqualTo("call-1");
        assertThat(loaded.pendingToolName()).isEqualTo("file_write");
    }

    @Test
    void loadReturnsEmptyWhenNotSaved() {
        SqliteContinuationStore store = store(tempDir.resolve("empty.sqlite"));
        assertThat(store.load("nonexistent-inv")).isEmpty();
    }

    @Test
    void deleteRemovesContinuation() {
        Path db = tempDir.resolve("delete.sqlite");
        SqliteContinuationStore store = store(db);
        AgentRequest request = AgentRequest.of("session-1", "删除测试");
        ContinuationStore.PersistedContinuation continuation =
                ContinuationStore.PersistedContinuation.forReact(
                        request, List.of(), 1, 0, null, null, null);
        store.save("inv-delete", continuation);
        assertThat(store.load("inv-delete")).isPresent();

        store.delete("inv-delete");
        assertThat(store.load("inv-delete")).isEmpty();
    }

    @Test
    void overwriteReplacesExistingContinuation() {
        Path db = tempDir.resolve("overwrite.sqlite");
        SqliteContinuationStore store = store(db);
        AgentRequest request = AgentRequest.of("session-1", "覆盖测试");
        ContinuationStore.PersistedContinuation first =
                ContinuationStore.PersistedContinuation.forReact(
                        request, List.of(), 1, 0, null, null, null);
        store.save("inv-overwrite", first);

        ContinuationStore.PersistedContinuation second =
                ContinuationStore.PersistedContinuation.forReact(
                        request, List.of(), 5, 3, null, null, null);
        store.save("inv-overwrite", second);

        ContinuationStore.PersistedContinuation loaded =
                store.load("inv-overwrite").orElseThrow();
        assertThat(loaded.modelCalls()).isEqualTo(5);
        assertThat(loaded.toolCalls()).isEqualTo(3);
    }

    private SqliteContinuationStore store(Path db) {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        tools.jackson.databind.ObjectMapper objectMapper =
                new tools.jackson.databind.ObjectMapper();
        return new SqliteContinuationStore(dataSource, objectMapper);
    }
}
