package com.github.agentos.server;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.kernel.PendingActionType;
import com.github.agentos.server.controller.AgentController;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerStreamTest {

    @Test
    void streamsRuntimeEventsAndFinalState() throws Exception {
        AgentLoop loop = new AgentLoop() {
            @Override
            public AgentState run(
                    AgentRequest request, AgentContext context, AgentState runningState) {
                return runningState.complete("done");
            }

            @Override
            public AgentState run(
                    AgentRequest request,
                    AgentContext context,
                    AgentState runningState,
                    AgentEventSink eventSink) {
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.RUN_STARTED,
                        request.sessionId(),
                        request.objective(),
                        Map.of("agentId", context.agentId())));
                return runningState.complete("done");
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new AgentController(
                            new AgentRuntime(loop), executor, new AgentRunTaskRegistry())).build();
            MvcResult started = mockMvc.perform(post("/api/agents/runs/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .content("""
                                    {"sessionId":"stream-1","input":"hello"}
                                    """))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(started))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(content().string(containsString("event:run_started")))
                    .andExpect(content().string(containsString("event:state")))
                    .andExpect(content().string(containsString("\"status\":\"COMPLETED\"")))
                    .andExpect(content().string(containsString("\"output\":\"done\"")));
        }
    }

    @Test
    void exposesPendingActionAndResumesAfterApproval() throws Exception {
        AgentLoop loop = new AgentLoop() {
            @Override
            public AgentState run(
                    AgentRequest request, AgentContext context, AgentState runningState) {
                context.invocation().waitFor(new PendingAction(
                        "approval-1", PendingActionType.HUMAN_APPROVAL,
                        "批准写入", "写入 report.docx", Map.of("toolName", "file_write")));
                return runningState.waitForAction("写入 report.docx");
            }

            @Override
            public AgentState resume(
                    AgentRequest request, AgentContext context, AgentState runningState,
                    AgentCheckpoint checkpoint, PendingActionResolution resolution,
                    AgentEventSink sink) {
                return runningState.complete("written");
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRuntime runtime = new AgentRuntime(loop);
            MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new AgentController(runtime, executor, new AgentRunTaskRegistry())).build();

            mockMvc.perform(post("/api/agents/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"approval-session","input":"write report"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(content().string(containsString("\"status\":\"WAITING\"")))
                    .andExpect(content().string(containsString(
                            "\"pendingActionId\":\"approval-1\"")));

            String invocationId = runtime.latestInvocation("approval-session")
                    .orElseThrow().invocationId();
            mockMvc.perform(get("/api/agents/approval-session/pending-action"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(invocationId)))
                    .andExpect(content().string(containsString("批准写入")));

            mockMvc.perform(post(
                            "/api/agents/invocations/{invocationId}/resolution", invocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"pendingActionId":"approval-1","approved":true}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"status\":\"COMPLETED\"")))
                    .andExpect(content().string(containsString("\"output\":\"written\"")));
        }
    }
}
