package com.github.agentos.server;

import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.kernel.SessionService;
import com.github.agentos.server.controller.SessionController;
import com.github.agentos.server.handler.AgentExceptionHandler;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.SessionAuthorization;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 会话快照查询接口测试。 */
class SessionControllerTest {

    private static final String USER_ID = "user-1";
    private static final String SECONDARY_USER_ID = "user-2";
    private static final String ADMIN_ID = "admin";

    @Test
    void listsRecentSessionsNewestFirst() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        service.applyDelta("session-1", Map.of("lastObjective", "读取项目"));
        Thread.sleep(5);
        service.getOrCreate("session-2", SECONDARY_USER_ID);
        service.applyDelta("session-2", Map.of("lastObjective", "查询天气", "turnCount", 2L));

        mvc(service, SECONDARY_USER_ID).perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sessionId").value("session-2"))
                .andExpect(jsonPath("$[0].userId").value(SECONDARY_USER_ID))
                .andExpect(jsonPath("$[0].stateKeys.length()").value(2))
                .andExpect(jsonPath("$[0].state.lastObjective").value("查询天气"));
    }

    @Test
    void listRespectsLimit() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        service.getOrCreate("session-2", USER_ID);

        mvc(service).perform(get("/api/sessions").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pageReturnsItemsTotalAndContinuationState() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        Thread.sleep(5);
        service.getOrCreate("session-2", USER_ID);
        Thread.sleep(5);
        service.getOrCreate("session-3", USER_ID);

        mvc(service).perform(get("/api/sessions/page")
                        .param("offset", "1")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].sessionId").value("session-2"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void listRejectsOutOfRangeLimit() throws Exception {
        MockMvc mvc = mvc(new InMemorySessionService());

        mvc.perform(get("/api/sessions").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions").param("limit", "500"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions/page").param("offset", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void singleSessionReturnsSnapshotOr404() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/sessions/{sessionId}", "session-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"));
        mvc.perform(get("/api/sessions/{sessionId}", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatesTitleAndDeletesSessionSnapshot() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        MockMvc mvc = mvc(service);

        mvc.perform(patch("/api/sessions/{sessionId}", "session-1")
                        .contentType("application/json")
                        .content("{\"title\":\"重庆天气\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.displayTitle").value("重庆天气"));

        mvc.perform(patch("/api/sessions/{sessionId}", "session-1")
                        .contentType("application/json")
                        .content("{\"pinned\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.pinned").value(true))
                .andExpect(jsonPath("$.state.pinnedAt").isNotEmpty());

        mvc.perform(patch("/api/sessions/{sessionId}", "session-1")
                        .contentType("application/json")
                        .content("{\"pinned\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state.pinned").value(false))
                .andExpect(jsonPath("$.state.pinnedAt").value(""));

        mvc.perform(delete("/api/sessions/{sessionId}", "session-1"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/sessions/{sessionId}", "session-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchDeleteReturnsDeletedCountAndMissingSessions() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        service.getOrCreate("session-2", USER_ID);
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/sessions/batch-delete")
                        .contentType("application/json")
                        .content("{\"sessionIds\":[\"session-1\",\"missing\",\"session-2\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.deleted").value(2))
                .andExpect(jsonPath("$.notFound[0]").value("missing"));
        mvc.perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void batchDeleteRejectsAnEmptyRequest() throws Exception {
        mvc(new InMemorySessionService()).perform(post("/api/sessions/batch-delete")
                        .contentType("application/json")
                        .content("{\"sessionIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingRunningSessionReturns409AndKeepsSession() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        AgentRunTaskRegistry registry = new AgentRunTaskRegistry();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            registry.start("session-1", executor, () -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            org.assertj.core.api.Assertions.assertThat(
                    started.await(2, TimeUnit.SECONDS)).isTrue();
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new SessionController(
                            service, new SessionAuthorization(service), registry))
                    .setControllerAdvice(new AgentExceptionHandler())
                    .defaultRequest(get("/").requestAttr(
                            RequestIdentity.REQUEST_ATTRIBUTE,
                            new RequestIdentity("default-team", USER_ID, Set.of())))
                    .build();

            mvc.perform(delete("/api/sessions/{sessionId}", "session-1"))
                    .andExpect(status().isConflict());
            org.assertj.core.api.Assertions.assertThat(
                    service.findByUser("session-1", USER_ID)).isPresent();
            release.countDown();
        } finally {
            release.countDown();
        }
    }

    @Test
    void deleteAsOwnerReturns204EvenWhenStaleTokenHasNoRoles() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        MockMvc owner = MockMvcBuilders.standaloneSetup(controller(service))
                .setControllerAdvice(new AgentExceptionHandler())
                .defaultRequest(get("/").requestAttr(
                        RequestIdentity.REQUEST_ATTRIBUTE,
                        new RequestIdentity("default-team", USER_ID, Set.of())))
                .build();
        owner.perform(delete("/api/sessions/{sessionId}", "session-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteReturns404ForForeignOwnedSessionWithoutAdmin() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service))
                .setControllerAdvice(new AgentExceptionHandler())
                .defaultRequest(get("/").requestAttr(
                        RequestIdentity.REQUEST_ATTRIBUTE,
                        new RequestIdentity("default-team", SECONDARY_USER_ID, Set.of())))
                .build();
        mvc.perform(delete("/api/sessions/{sessionId}", "session-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCannotDeleteForeignSessions() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        service.getOrCreate("session-2", SECONDARY_USER_ID);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service))
                .setControllerAdvice(new AgentExceptionHandler())
                .defaultRequest(get("/").requestAttr(
                        RequestIdentity.REQUEST_ATTRIBUTE,
                        new RequestIdentity("default-team", ADMIN_ID, Set.of("ADMIN"))))
                .build();
        mvc.perform(delete("/api/sessions/{sessionId}", "session-1"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/sessions/{sessionId}", "session-2"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/sessions/{sessionId}", "missing"))
                .andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(service.find("session-1")).isPresent();
        org.assertj.core.api.Assertions.assertThat(service.find("session-2")).isPresent();
    }

    @Test
    void adminBatchDeleteTreatsForeignSessionsAsMissing() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", USER_ID);
        service.getOrCreate("session-2", SECONDARY_USER_ID);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(service))
                .setControllerAdvice(new AgentExceptionHandler())
                .defaultRequest(get("/").requestAttr(
                        RequestIdentity.REQUEST_ATTRIBUTE,
                        new RequestIdentity("default-team", ADMIN_ID, Set.of("ADMIN"))))
                .build();
        mvc.perform(post("/api/sessions/batch-delete")
                        .contentType("application/json")
                        .content("{\"sessionIds\":[\"session-1\",\"session-2\",\"missing\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.deleted").value(0))
                .andExpect(jsonPath("$.notFound.length()").value(3));
        org.assertj.core.api.Assertions.assertThat(service.find("session-1")).isPresent();
        org.assertj.core.api.Assertions.assertThat(service.find("session-2")).isPresent();
    }

    private static MockMvc mvc(SessionService service) {
        return mvc(service, USER_ID);
    }

    private static MockMvc mvc(SessionService service, String userId) {
        return MockMvcBuilders.standaloneSetup(controller(service))
                .setControllerAdvice(new AgentExceptionHandler())
                .defaultRequest(get("/").requestAttr(
                        RequestIdentity.REQUEST_ATTRIBUTE,
                        new RequestIdentity("default-team", userId, Set.of())))
                .build();
    }

    private static SessionController controller(SessionService service) {
        return new SessionController(
                service, new SessionAuthorization(service), new AgentRunTaskRegistry());
    }
}
