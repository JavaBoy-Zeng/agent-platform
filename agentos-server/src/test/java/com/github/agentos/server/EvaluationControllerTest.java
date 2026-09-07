package com.github.agentos.server;

import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.InMemoryAgentEventStore;
import com.github.agentos.kernel.eval.ToolTrajectoryEvaluator;
import com.github.agentos.server.controller.EvaluationController;
import com.github.agentos.server.eval.EvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 评估接口测试。 */
class EvaluationControllerTest {

    @Test
    void evaluate_passedCase_returnsFullResult() throws Exception {
        AgentEventStore store = storeWithRun(
                "inv-1", "web_search", "MiniMax 发布了新模型");
        MockMvc mvc = mvc(store);

        mvc.perform(post("/api/evaluations/{invocationId}", "inv-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caseId": "case-1",
                                  "expectedToolSequence": ["web_search"],
                                  "forbiddenTools": ["file_write"],
                                  "maxToolCalls": 3,
                                  "requiredResponseKeywords": ["MiniMax"],
                                  "requireCompleted": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value("case-1"))
                .andExpect(jsonPath("$.invocationId").value("inv-1"))
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.score").value(1.0))
                .andExpect(jsonPath("$.findings.length()").value(5))
                .andExpect(jsonPath("$.actualToolSequence[0]").value("web_search"))
                .andExpect(jsonPath("$.finalResponse").value("MiniMax 发布了新模型"))
                .andExpect(jsonPath("$.toolCallCount").value(1))
                .andExpect(jsonPath("$.failedToolCallCount").value(0));
    }

    @Test
    void evaluate_failedCase_reportsViolatedFindings() throws Exception {
        AgentEventStore store = storeWithRun("inv-2", "file_write", "已写入");
        MockMvc mvc = mvc(store);

        mvc.perform(post("/api/evaluations/{invocationId}", "inv-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "caseId": "case-2",
                                  "forbiddenTools": ["file_write"],
                                  "requiredResponseKeywords": ["结论"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.score").value(0.0))
                .andExpect(jsonPath("$.findings.length()").value(2));
    }

    @Test
    void evaluate_unknownInvocation_returns404() throws Exception {
        MockMvc mvc = mvc(new InMemoryAgentEventStore());

        mvc.perform(post("/api/evaluations/{invocationId}", "missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    private static MockMvc mvc(AgentEventStore store) {
        return MockMvcBuilders.standaloneSetup(new EvaluationController(
                new EvaluationService(store, new ToolTrajectoryEvaluator()),
                TestSessionAuthorizations.owned("session-1"))).build();
    }

    /** 构造一次包含单工具调用与最终回答的事件流。 */
    private static AgentEventStore storeWithRun(
            String invocationId, String toolName, String finalResponse) {
        AgentEventStore store = new InMemoryAgentEventStore();
        store.append(event(invocationId, AgentEventType.AGENT_STARTED, "", Map.of()));
        store.append(event(invocationId, AgentEventType.TOOL_CALL_STARTED, "",
                Map.of("toolName", toolName)));
        store.append(event(invocationId, AgentEventType.TOOL_CALL_COMPLETED, "",
                Map.of("toolName", toolName, "success", true)));
        store.append(event(invocationId, AgentEventType.AGENT_COMPLETED, finalResponse,
                Map.of("status", "COMPLETED")));
        return store;
    }

    private static AgentEvent event(
            String invocationId, AgentEventType type, String message, Map<String, Object> data) {
        return new DefaultAgentEvent(
                "event-" + Math.random(), "session-1", invocationId, "main-agent",
                Instant.now(), type, message, data);
    }
}
