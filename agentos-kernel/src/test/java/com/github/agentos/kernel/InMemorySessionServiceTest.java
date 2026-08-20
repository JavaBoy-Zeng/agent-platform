package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 进程内会话服务测试。 */
class InMemorySessionServiceTest {

    @Test
    void recentReturnsSessionsOrderedByLastActiveDescending() throws InterruptedException {
        InMemorySessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", "user-1");
        service.getOrCreate("session-2", "user-1");
        // applyDelta 刷新活跃时间；Windows 时钟精度可能让相邻调用取到同一瞬间，故显式间隔。
        service.applyDelta("session-1", Map.of("step", 1L));
        Thread.sleep(5);
        service.applyDelta("session-2", Map.of("step", 2L));

        assertThat(service.recent(10)).extracting(Session::sessionId)
                .containsExactly("session-2", "session-1");
    }

    @Test
    void recentAppliesLimit() {
        InMemorySessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", "user-1");
        service.applyDelta("session-2", Map.of("step", 1L));

        assertThat(service.recent(1)).hasSize(1);
    }

    @Test
    void recentRejectsNonPositiveLimit() {
        InMemorySessionService service = new InMemorySessionService();

        assertThatThrownBy(() -> service.recent(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be positive");
    }

    @Test
    void recentIsEmptyWithoutSessions() {
        assertThat(new InMemorySessionService().recent(5)).isEmpty();
    }
}
