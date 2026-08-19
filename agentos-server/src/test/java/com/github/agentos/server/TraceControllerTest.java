package com.github.agentos.server;

import com.github.agentos.kernel.trace.InMemoryTraceStore;
import com.github.agentos.kernel.trace.Span;
import com.github.agentos.kernel.trace.TraceStore;
import com.github.agentos.server.controller.TraceController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 链路追踪查询接口测试。 */
class TraceControllerTest {

    @Test
    void queryByInvocationId_returnsTrace() throws Exception {
        TraceStore store = new InMemoryTraceStore();
        Instant t0 = Instant.now();
        Instant t1 = t0.plusMillis(50);
        Instant t2 = t0.plusMillis(100);
        store.append(new Span("inv-1", "s0", "", "agent-run", Span.Kind.ROOT,
                t0, t2, Span.Status.OK,
                Map.of("sessionId", "sess-1", "agentId", "main-agent"), Map.of()));
        store.append(new Span("inv-1", "s1", "s0", "model-call", Span.Kind.MODEL,
                t1, t2, Span.Status.OK, Map.of("model", "gpt-4"), Map.of()));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceController(store)).build();

        mvc.perform(get("/api/traces/{invocationId}", "inv-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("inv-1"))
                .andExpect(jsonPath("$.sessionId").value("sess-1"))
                .andExpect(jsonPath("$.agentId").value("main-agent"))
                .andExpect(jsonPath("$.spanCount").value(2))
                .andExpect(jsonPath("$.finishedSpanCount").value(2))
                .andExpect(jsonPath("$.spans[0].spanId").value("s0"))
                .andExpect(jsonPath("$.spans[0].kind").value("ROOT"))
                .andExpect(jsonPath("$.spans[1].parentSpanId").value("s0"))
                .andExpect(jsonPath("$.spans[1].kind").value("MODEL"));
    }

    @Test
    void queryByInvocationId_unknownReturns404() throws Exception {
        TraceStore store = new InMemoryTraceStore();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceController(store)).build();

        mvc.perform(get("/api/traces/{invocationId}", "missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void queryBySessionId_returnsGroupedTraces() throws Exception {
        TraceStore store = new InMemoryTraceStore();
        Instant now = Instant.now();
        store.append(new Span("trace-1", "s1", "", "root", Span.Kind.ROOT,
                now, now, Span.Status.OK,
                Map.of("sessionId", "sess-1", "agentId", "a"), Map.of()));
        store.append(new Span("trace-2", "s2", "", "root", Span.Kind.ROOT,
                now, now, Span.Status.OK,
                Map.of("sessionId", "sess-1", "agentId", "b"), Map.of()));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceController(store)).build();

        mvc.perform(get("/api/traces").param("sessionId", "sess-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].traceId").value("trace-1"))
                .andExpect(jsonPath("$[1].traceId").value("trace-2"));
    }

    @Test
    void queryBySessionId_emptyReturnsEmptyArray() throws Exception {
        TraceStore store = new InMemoryTraceStore();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TraceController(store)).build();

        mvc.perform(get("/api/traces").param("sessionId", "empty"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
