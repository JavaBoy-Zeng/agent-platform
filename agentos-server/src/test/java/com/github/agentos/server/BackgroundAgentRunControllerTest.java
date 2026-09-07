package com.github.agentos.server;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InMemoryAgentEventStore;
import com.github.agentos.server.controller.BackgroundAgentRunController;
import com.github.agentos.server.history.SessionHistoryService;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import com.github.agentos.server.run.AgentRunCoordinator;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 后台运行快照、断点补播和显式取消的接口测试。 */
class BackgroundAgentRunControllerTest {

    @Test
    void boundsRunAndEventRetention() throws Exception {
        AgentLoop loop = new AgentLoop() {
            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context, AgentState runningState) {
                return runningState.complete("done");
            }

            @Override
            public AgentState run(
                    AgentRequest request,
                    InvocationContext context,
                    AgentState runningState,
                    AgentEventSink eventSink) {
                for (int index = 1; index <= 3; index++) {
                    eventSink.emit(AgentRunEvent.of(
                            AgentRunEvent.Type.OUTPUT_DELTA,
                            request.sessionId(),
                            "delta-" + index,
                            Map.of("sequence", index)));
                }
                return runningState.complete("done");
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunCoordinator coordinator = new AgentRunCoordinator(
                    new AgentRunner(loop), executor, new AgentRunTaskRegistry(), 1, 2);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new BackgroundAgentRunController(
                            coordinator, historyService(),
                            TestSessionAuthorizations.owned("retention-2"))).build();

            AgentRunCoordinator.RunSnapshot first = coordinator.start(
                    AgentRequest.of("retention-1", "first"),
                    InvocationContext.of("agent"));
            awaitTerminal(coordinator, first.runId());
            AgentRunCoordinator.RunSnapshot second = coordinator.start(
                    AgentRequest.of("retention-2", "second"),
                    InvocationContext.of("agent"));
            awaitTerminal(coordinator, second.runId());

            assertThat(coordinator.find(first.runId())).isEmpty();
            assertThat(coordinator.list()).hasSize(1);

            MvcResult subscribed = mvc.perform(get(
                                    "/api/agent-runs/{runId}/events?after=0", second.runId())
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            subscribed.getAsyncResult(2_000);
            mvc.perform(asyncDispatch(subscribed))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                            "Cache-Control", "no-cache, no-transform"))
                    .andExpect(header().string("X-Accel-Buffering", "no"))
                    .andExpect(content().string(not(containsString("id:1\n"))))
                    .andExpect(content().string(not(containsString("id:2\n"))))
                    .andExpect(content().string(containsString("id:3")))
                    .andExpect(content().string(containsString("id:4")));
        }
    }

    @Test
    void replaysOnlyEventsAfterCursorAndReturnsFinalSnapshot() throws Exception {
        AgentLoop loop = new AgentLoop() {
            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context, AgentState runningState) {
                return runningState.complete("done");
            }

            @Override
            public AgentState run(
                    AgentRequest request,
                    InvocationContext context,
                    AgentState runningState,
                    AgentEventSink eventSink) {
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.RUN_STARTED, request.sessionId(),
                        "started", Map.of()));
                eventSink.emit(AgentRunEvent.of(
                        AgentRunEvent.Type.OUTPUT_DELTA, request.sessionId(),
                        "done", Map.of("sequence", 0)));
                return runningState.complete("done");
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunCoordinator coordinator = new AgentRunCoordinator(
                    new AgentRunner(loop), executor, new AgentRunTaskRegistry());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new BackgroundAgentRunController(
                            coordinator, historyService(), TestSessionAuthorizations.owned())).build();

            String body = mvc.perform(post("/api/agent-runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"recoverable-1","input":"hello"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString();
            String runId = JsonPath.read(body, "$.runId");
            awaitTerminal(coordinator, runId);

            mvc.perform(get("/api/agent-runs/{runId}", runId))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"status\":\"COMPLETED\"")))
                    .andExpect(content().string(containsString("\"output\":\"done\"")));

            MvcResult subscribed = mvc.perform(get(
                                    "/api/agent-runs/{runId}/events?after=1", runId)
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            subscribed.getAsyncResult(2_000);

            mvc.perform(asyncDispatch(subscribed))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("id:1\n"))))
                    .andExpect(content().string(containsString("id:2")))
                    .andExpect(content().string(containsString("event:assistant_message")))
                    .andExpect(content().string(containsString("event:state")))
                    .andExpect(content().string(containsString("\"lastSequence\":3")));
        }
    }

    @Test
    void disconnectDoesNotCancelButExplicitCancelDoes() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AgentLoop loop = (request, context, running) -> {
            CountDownLatch started = request.sessionId().equals("disconnect-1")
                    ? firstStarted : secondStarted;
            started.countDown();
            try {
                if (request.sessionId().equals("disconnect-1")) {
                    firstRelease.await();
                    return running.complete("still running");
                }
                new CountDownLatch(1).await();
                return running.complete("unexpected");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CancellationException("cancelled");
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunCoordinator coordinator = new AgentRunCoordinator(
                    new AgentRunner(loop), executor, new AgentRunTaskRegistry());

            AgentRunCoordinator.RunSnapshot disconnectedRun = coordinator.start(
                    new AgentRequest("disconnect-1", "hello", Map.of()),
                    InvocationContext.of("agent"));
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var emitter = coordinator.stream(disconnectedRun.runId(), 0).orElseThrow();
            emitter.complete();
            firstRelease.countDown();
            AgentRunCoordinator.RunSnapshot completed = awaitTerminal(
                    coordinator, disconnectedRun.runId());
            assertThat(completed.state().status()).isEqualTo(AgentState.Status.COMPLETED);

            AgentRunCoordinator.RunSnapshot cancellableRun = coordinator.start(
                    new AgentRequest("cancel-1", "hello", Map.of()),
                    InvocationContext.of("agent"));
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            AgentRunCoordinator.CancelResult cancellation = coordinator.cancel(
                    cancellableRun.runId()).orElseThrow();
            assertThat(cancellation.interruptRequested()).isTrue();
            AgentRunCoordinator.RunSnapshot cancelled = awaitTerminal(
                    coordinator, cancellableRun.runId());
            assertThat(cancelled.state().status()).isEqualTo(AgentState.Status.CANCELLED);
        }
    }

    @Test
    void listsRunsNewestFirst() throws Exception {
        AgentLoop loop = (request, context, runningState) -> runningState.complete("done");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunCoordinator coordinator = new AgentRunCoordinator(
                    new AgentRunner(loop), executor, new AgentRunTaskRegistry());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new BackgroundAgentRunController(
                            coordinator, historyService(),
                            TestSessionAuthorizations.owned("list-1", "list-2"))).build();

            AgentRunCoordinator.RunSnapshot first = coordinator.start(
                    new AgentRequest("list-1", "hello", Map.of()),
                    InvocationContext.of("agent"));
            awaitTerminal(coordinator, first.runId());
            // 创建时间以毫秒记录；显式间隔保证两次运行的排序稳定。
            Thread.sleep(5);
            AgentRunCoordinator.RunSnapshot second = coordinator.start(
                    new AgentRequest("list-2", "hello", Map.of()),
                    InvocationContext.of("agent"));
            awaitTerminal(coordinator, second.runId());

            mvc.perform(get("/api/agent-runs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].sessionId").value("list-2"))
                    .andExpect(jsonPath("$[1].sessionId").value("list-1"))
                    .andExpect(jsonPath("$[0].state.status").value("COMPLETED"));
        }
    }

    /**
     * 控制台只走后台运行入口。这里曾经不注入会话历史，导致“我叫曾智”下一轮就失忆，
     * 模型甚至声称“每次对话都是独立的”。运行入口必须携带历史属性。
     */
    @Test
    void injectsConversationHistoryIntoBackgroundRuns() throws Exception {
        java.util.concurrent.atomic.AtomicReference<AgentRequest> observed =
                new java.util.concurrent.atomic.AtomicReference<>();
        AgentLoop loop = (request, context, runningState) -> {
            observed.set(request);
            return runningState.complete("你好，曾智。");
        };
        InMemoryAgentEventStore eventStore = new InMemoryAgentEventStore();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunner runner = new AgentRunner(
                    loop, com.github.agentos.kernel.AgentEventPublisher.NOOP, eventStore);
            AgentRunCoordinator coordinator = new AgentRunCoordinator(
                    runner, executor, new AgentRunTaskRegistry());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new BackgroundAgentRunController(
                    coordinator, new SessionHistoryService(eventStore, 5, 400),
                    TestSessionAuthorizations.owned())).build();

            String firstRunId = JsonPath.read(mvc.perform(post("/api/agent-runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"identity-1","input":"我叫曾智"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString(), "$.runId");
            awaitTerminal(coordinator, firstRunId);
            assertThat(observed.get().attributes()).doesNotContainKey("conversationHistory");

            String secondRunId = JsonPath.read(mvc.perform(post("/api/agent-runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"identity-1","input":"我是谁"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString(), "$.runId");
            awaitTerminal(coordinator, secondRunId);

            assertThat(observed.get().attributes())
                    .containsEntry("conversationHistory", "用户：我叫曾智\n助手：你好，曾智。");
        }
    }

    private static SessionHistoryService historyService() {
        return new SessionHistoryService(
                new com.github.agentos.kernel.InMemoryAgentEventStore(), 5, 400);
    }

    private static AgentRunCoordinator.RunSnapshot awaitTerminal(
            AgentRunCoordinator coordinator, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            AgentRunCoordinator.RunSnapshot snapshot = coordinator.find(runId).orElseThrow();
            if (snapshot.state().status() != AgentState.Status.RUNNING) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run did not reach a terminal state: " + runId);
    }
}
