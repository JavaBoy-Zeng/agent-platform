package com.github.agentos.server;

import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.EventActions;
import com.github.agentos.kernel.InMemoryAgentEventStore;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.server.controller.AgentEventController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 领域事件轨迹查询接口测试。 */
class AgentEventControllerTest {

    private static final Instant BASE = Instant.parse("2026-08-20T10:00:00Z");

    @Test
    void queryBySessionId_groupsEventsByInvocation() throws Exception {
        AgentEventStore store = new InMemoryAgentEventStore();
        store.append(event("e1", "sess-1", "inv-1", AgentEventType.AGENT_STARTED, 0));
        store.append(event("e2", "sess-1", "inv-1", AgentEventType.PLAN_CREATED, 10));
        store.append(event("e3", "sess-1", "inv-1", AgentEventType.AGENT_COMPLETED, 20));
        store.append(event("e4", "sess-1", "inv-2", AgentEventType.AGENT_STARTED, 30));

        MockMvc mvc = mvc(store, "sess-1");

        mvc.perform(get("/api/events").param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // 最近的 Invocation 排在最前。
                .andExpect(jsonPath("$[0].invocationId").value("inv-2"))
                .andExpect(jsonPath("$[1].invocationId").value("inv-1"))
                .andExpect(jsonPath("$[1].eventCount").value(3))
                .andExpect(jsonPath("$[1].terminalType").value("AGENT_COMPLETED"))
                .andExpect(jsonPath("$[1].events[1].type").value("PLAN_CREATED"));
    }

    @Test
    void queryBySessionId_filtersByType() throws Exception {
        AgentEventStore store = new InMemoryAgentEventStore();
        store.append(event("e1", "sess-1", "inv-1", AgentEventType.AGENT_STARTED, 0));
        store.append(event("e2", "sess-1", "inv-1", AgentEventType.TOOL_CALL_COMPLETED, 5));

        MockMvc mvc = mvc(store, "sess-1");

        mvc.perform(get("/api/events")
                        .param("sessionId", "sess-1")
                        .param("type", "tool_call_completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventCount").value(1))
                .andExpect(jsonPath("$[0].events[0].type").value("TOOL_CALL_COMPLETED"));
    }

    @Test
    void queryBySessionId_unknownTypeIsIgnored() throws Exception {
        AgentEventStore store = new InMemoryAgentEventStore();
        store.append(event("e1", "sess-1", "inv-1", AgentEventType.AGENT_STARTED, 0));

        MockMvc mvc = mvc(store, "sess-1");

        mvc.perform(get("/api/events")
                        .param("sessionId", "sess-1")
                        .param("type", "not-an-event-type"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventCount").value(1));
    }

    @Test
    void queryByInvocationId_returnsTraceWithActions() throws Exception {
        AgentEventStore store = new InMemoryAgentEventStore();
        store.append(new DefaultAgentEvent(
                "e1", "sess-1", "inv-1", "main-agent", BASE,
                AgentEventType.HUMAN_ACTION_REQUIRED, "需要审批",
                Map.of("pendingActionId", "pa-1"), EventActions.approval()));

        MockMvc mvc = mvc(store, "sess-1");

        mvc.perform(get("/api/events/{invocationId}", "inv-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.agentId").value("main-agent"))
                .andExpect(jsonPath("$.eventCount").value(1))
                .andExpect(jsonPath("$.terminalType").value(""))
                .andExpect(jsonPath("$.events[0].data.pendingActionId").value("pa-1"))
                .andExpect(jsonPath("$.events[0].actions.requireApproval").value(true));
    }

    @Test
    void queryByInvocationId_unknownReturns404() throws Exception {
        MockMvc mvc = mvc(new InMemoryAgentEventStore());

        mvc.perform(get("/api/events/{invocationId}", "missing"))
                .andExpect(status().isNotFound());
    }

    private static DefaultAgentEvent event(
            String eventId, String sessionId, String invocationId,
            AgentEventType type, long offsetMillis) {
        return new DefaultAgentEvent(
                eventId, sessionId, invocationId, "main-agent",
                BASE.plusMillis(offsetMillis), type, "message", Map.of());
    }

    private static MockMvc mvc(AgentEventStore store, String... ownedSessions) {
        return MockMvcBuilders.standaloneSetup(new AgentEventController(
                store, TestSessionAuthorizations.owned(ownedSessions))).build();
    }
}
