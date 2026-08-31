package com.github.agentos.server;

import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.kernel.SessionService;
import com.github.agentos.server.controller.SessionController;
import com.github.agentos.server.handler.AgentExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 会话快照查询接口测试。 */
class SessionControllerTest {

    @Test
    void listsRecentSessionsNewestFirst() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", "user-1");
        service.applyDelta("session-1", Map.of("lastObjective", "读取项目"));
        Thread.sleep(5);
        service.getOrCreate("session-2", "user-2");
        service.applyDelta("session-2", Map.of("lastObjective", "查询天气", "turnCount", 2L));

        mvc(service).perform(get("/api/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sessionId").value("session-2"))
                .andExpect(jsonPath("$[0].userId").value("user-2"))
                .andExpect(jsonPath("$[0].stateKeys.length()").value(2))
                .andExpect(jsonPath("$[0].state.lastObjective").value("查询天气"))
                .andExpect(jsonPath("$[1].sessionId").value("session-1"));
    }

    @Test
    void listRespectsLimit() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", "user-1");
        service.getOrCreate("session-2", "user-1");

        mvc(service).perform(get("/api/sessions").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void pageReturnsItemsTotalAndContinuationState() throws Exception {
        SessionService service = new InMemorySessionService();
        service.getOrCreate("session-1", "user-1");
        Thread.sleep(5);
        service.getOrCreate("session-2", "user-1");
        Thread.sleep(5);
        service.getOrCreate("session-3", "user-1");

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
        service.getOrCreate("session-1", "user-1");
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
        service.getOrCreate("session-1", "user-1");
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
        service.getOrCreate("session-1", "user-1");
        service.getOrCreate("session-2", "user-1");
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

    private static MockMvc mvc(SessionService service) {
        return MockMvcBuilders.standaloneSetup(new SessionController(service))
                .setControllerAdvice(new AgentExceptionHandler())
                .build();
    }
}
