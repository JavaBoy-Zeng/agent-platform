package com.github.agentos.server;

import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.kernel.SessionService;
import com.github.agentos.server.controller.SessionController;
import com.github.agentos.server.handler.AgentExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    void listRejectsOutOfRangeLimit() throws Exception {
        MockMvc mvc = mvc(new InMemorySessionService());

        mvc.perform(get("/api/sessions").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/sessions").param("limit", "500"))
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

    private static MockMvc mvc(SessionService service) {
        return MockMvcBuilders.standaloneSetup(new SessionController(service))
                .setControllerAdvice(new AgentExceptionHandler())
                .build();
    }
}
