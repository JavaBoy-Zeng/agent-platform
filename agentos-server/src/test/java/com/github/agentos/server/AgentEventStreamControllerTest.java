package com.github.agentos.server;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.server.controller.AgentEventStreamController;
import com.github.agentos.server.history.SessionHistoryService;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 领域事件、token 与终态分流的 SSE 测试。 */
class AgentEventStreamControllerTest {

    @Test
    void streamsDomainEventsOnDedicatedNames() throws Exception {
        AgentLoop loop = (AgentRequest request, InvocationContext context, AgentState running) ->
                running.complete("done");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AgentEventStreamController(
                            new AgentRunner(loop), executor, new AgentRunTaskRegistry(),
                            new SessionHistoryService(
                                    new com.github.agentos.kernel.InMemoryAgentEventStore(),
                                    5, 400))).build();
            MvcResult started = mvc.perform(post("/api/agents/runs/event-stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .content("""
                                    {"sessionId":"event-stream-1","input":"hello"}
                                    """))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            started.getAsyncResult(2_000);

            mvc.perform(asyncDispatch(started))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("event:domain.agent_started")))
                    .andExpect(content().string(containsString("event:domain.agent_completed")))
                    .andExpect(content().string(containsString("event:state")))
                    .andExpect(content().string(containsString("\"output\":\"done\"")));
        }
    }
}
