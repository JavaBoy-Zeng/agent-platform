package com.github.agentos.server.persistence;

import com.github.agentos.kernel.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SQLite 会话服务测试：验证状态快照跨实例（重启）可见。 */
class SqliteSessionServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SqliteSessionService service(Path file) {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return new SqliteSessionService(dataSource, objectMapper);
    }

    @Test
    void persistsSessionStateAcrossRestart() {
        Path file = tempDir.resolve("sessions.sqlite");
        SqliteSessionService first = service(file);
        first.getOrCreate("session-1", "user-1");
        first.applyDelta("session-1", Map.of("city", "重庆", "turnCount", 3L));

        // 模拟进程重启：用同一数据库文件构造新实例。
        SqliteSessionService second = service(file);
        Session session = second.find("session-1").orElseThrow();

        assertThat(session.userId()).isEqualTo("user-1");
        assertThat(session.state().value("city")).isEqualTo("重庆");
        assertThat(session.state().longValue("turnCount", 0)).isEqualTo(3L);
    }

    @Test
    void applyDeltaCreatesMissingSessionAndMergesConsecutiveDeltas() {
        SqliteSessionService service = service(tempDir.resolve("sessions.sqlite"));

        service.applyDelta("session-1", Map.of("city", "重庆"));
        service.applyDelta("session-1", Map.of("unit", "celsius", "city", "北京"));

        Session session = service.find("session-1").orElseThrow();
        assertThat(session.state().value("city")).isEqualTo("北京");
        assertThat(session.state().value("unit")).isEqualTo("celsius");
    }

    @Test
    void getOrCreateKeepsExistingSessionUnchanged() {
        SqliteSessionService service = service(tempDir.resolve("sessions.sqlite"));
        service.getOrCreate("session-1", "user-1");
        service.applyDelta("session-1", Map.of("turnCount", 5L));

        Session session = service.getOrCreate("session-1", "user-2");

        assertThat(session.userId()).isEqualTo("user-1");
        assertThat(session.state().longValue("turnCount", 0)).isEqualTo(5L);
    }

    @Test
    void missingSessionIsEmpty() {
        SqliteSessionService service = service(tempDir.resolve("sessions.sqlite"));

        assertThat(service.find("no-such-session")).isEmpty();
    }

    @Test
    void recentReturnsSessionsOrderedByLastActiveDescending() throws InterruptedException {
        SqliteSessionService service = service(tempDir.resolve("sessions.sqlite"));
        service.getOrCreate("session-1", "user-1");
        service.getOrCreate("session-2", "user-1");
        // 活跃时间存的是毫秒；显式间隔避免相邻两次写入落在同一毫秒。
        service.applyDelta("session-1", Map.of("step", 1L));
        Thread.sleep(5);
        service.applyDelta("session-2", Map.of("step", 2L));

        assertThat(service.recent(10)).extracting(Session::sessionId)
                .containsExactly("session-2", "session-1");
        assertThat(service.recent(1)).extracting(Session::sessionId)
                .containsExactly("session-2");
        assertThat(service.count()).isEqualTo(2);
        assertThat(service.recent(1, 1)).extracting(Session::sessionId)
                .containsExactly("session-1");
    }

    @Test
    void recentRejectsNonPositiveLimit() {
        SqliteSessionService service = service(tempDir.resolve("sessions.sqlite"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recent(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.recent(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offset must not be negative");
    }

    @Test
    void deleteRemovesPersistedSessionSnapshot() {
        SqliteSessionService service = service(tempDir.resolve("sessions.sqlite"));
        service.getOrCreate("session-1", "user-1");

        assertThat(service.delete("session-1")).isTrue();
        assertThat(service.delete("session-1")).isFalse();
        assertThat(service.find("session-1")).isEmpty();
    }
}
