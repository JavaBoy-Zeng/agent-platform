package com.github.agentos.server;

import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunStatus;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.kernel.PendingActionType;
import com.github.agentos.kernel.InMemoryAgentEventStore;
import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.server.controller.BackgroundAgentRunController;
import com.github.agentos.server.history.SessionHistoryService;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import com.github.agentos.server.run.AgentRunCoordinator;
import com.github.agentos.server.run.AgentRunSnapshot;
import com.github.agentos.server.security.RequestIdentity;
import com.github.agentos.server.security.SessionAuthorization;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Set;
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
    void approvalResolutionReturnsAcceptedBeforeTheResumedWorkCompletes() throws Exception {
        CountDownLatch resumeStarted = new CountDownLatch(1);
        CountDownLatch releaseResume = new CountDownLatch(1);
        AgentLoop loop = new AgentLoop() {
            @Override
            public AgentState run(
                    AgentRequest request, InvocationContext context, AgentState runningState) {
                context.invocation().waitFor(new PendingAction(
                        "pending-web-1", PendingActionType.HUMAN_APPROVAL,
                        "批准网页读取", "读取目标网页", Map.of("toolName", "web_fetch")));
                return runningState.waitForAction("等待网页读取审批");
            }

            @Override
            public AgentState resume(
                    AgentRequest request,
                    InvocationContext context,
                    AgentState runningState,
                    AgentCheckpoint checkpoint,
                    PendingActionResolution resolution,
                    AgentEventSink eventSink) {
                resumeStarted.countDown();
                try {
                    if (!releaseResume.await(2, TimeUnit.SECONDS)) {
                        return runningState.fail("resume release timeout");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return runningState.fail("resume interrupted");
                }
                return runningState.complete("网页总结完成");
            }
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunner runner = new AgentRunner(loop);
            AgentRunCoordinator coordinator = new AgentRunCoordinator(
                    runner, executor, new AgentRunTaskRegistry());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new BackgroundAgentRunController(
                            coordinator, historyService(),
                            TestSessionAuthorizations.owned())).build();

            String initialRunId = JsonPath.read(mvc.perform(post("/api/agent-runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"approval-background-1","input":"读取网页"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString(), "$.runId");
            AgentRunSnapshot waiting = awaitTerminal(coordinator, initialRunId);
            assertThat(waiting.status()).isEqualTo(AgentRunStatus.WAITING);
            String invocationId = waiting.invocationId();

            String resumedBody = mvc.perform(post(
                            "/api/agent-runs/invocations/{invocationId}/resolution", invocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"pendingActionId":"pending-web-1","approved":true}
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string("Location", containsString("/api/agent-runs/")))
                    .andExpect(jsonPath("$.status").value("RUNNING"))
                    .andReturn().getResponse().getContentAsString();
            String resumedRunId = JsonPath.read(resumedBody, "$.runId");
            assertThat(resumedRunId).isEqualTo(initialRunId);
            assertThat(resumeStarted.await(1, TimeUnit.SECONDS)).isTrue();

            mvc.perform(post(
                            "/api/agent-runs/invocations/{invocationId}/resolution", invocationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"pendingActionId":"pending-web-1","approved":true}
                                    """))
                    .andExpect(status().isConflict());

            releaseResume.countDown();
            AgentRunSnapshot completed = awaitTerminal(
                    coordinator, resumedRunId);
            assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);
            assertThat(completed.output()).isEqualTo("网页总结完成");
            assertThat(completed.invocationId()).isEqualTo(invocationId);
        }
    }

    @Test
    void retainedRunBecomesInaccessibleAfterSessionSoftDelete() throws Exception {
        AgentLoop loop = (request, context, runningState) -> runningState.complete("done");
        InMemorySessionService sessions = new InMemorySessionService();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunCoordinator coordinator = new AgentRunCoordinator(
                    new AgentRunner(loop), executor, new AgentRunTaskRegistry());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new BackgroundAgentRunController(
                    coordinator, historyService(), new SessionAuthorization(sessions))).build();

            String runId = JsonPath.read(mvc.perform(post("/api/agent-runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"soft-deleted-run","input":"hello"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString(), "$.runId");
            awaitTerminal(coordinator, runId);
            assertThat(sessions.deleteByUser("soft-deleted-run", "default-user")).isTrue();

            mvc.perform(get("/api/agent-runs/{runId}", runId))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/agent-runs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
    }

    @Test
    void ignoresClientSuppliedIdentityAndUsesAuthenticatedAccount() throws Exception {
        java.util.concurrent.atomic.AtomicReference<InvocationContext> observed =
                new java.util.concurrent.atomic.AtomicReference<>();
        AgentLoop loop = (request, context, runningState) -> {
            observed.set(context);
            return runningState.complete("done");
        };

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AgentRunCoordinator coordinator = new AgentRunCoordinator(
                    new AgentRunner(loop), executor, new AgentRunTaskRegistry());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new BackgroundAgentRunController(
                            coordinator, historyService(),
                            TestSessionAuthorizations.ownedBy("demo")))
                    .defaultRequest(get("/").requestAttr(
                            RequestIdentity.REQUEST_ATTRIBUTE,
                            new RequestIdentity("trusted-team", "demo", Set.of())))
                    .build();

            String runId = JsonPath.read(mvc.perform(post("/api/agent-runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"teamId":"forged-team","userId":"admin",
                                     "sessionId":"identity-bound","input":"hello"}
                                    """))
                    .andExpect(status().isAccepted())
                    .andReturn().getResponse().getContentAsString(), "$.runId");
            awaitTerminal(coordinator, runId);

            assertThat(observed.get().teamId()).isEqualTo("trusted-team");
            assertThat(observed.get().userId()).isEqualTo("demo");
        }
    }

    @Test
    void boundsInMemoryRetentionWithoutLosingDurableRuns() throws Exception {
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

            AgentRunSnapshot first = coordinator.start(
                    AgentRequest.of("retention-1", "first"),
                    InvocationContext.of("agent"));
            awaitTerminal(coordinator, first.runId());
            AgentRunSnapshot second = coordinator.start(
                    AgentRequest.of("retention-2", "second"),
                    InvocationContext.of("agent"));
            awaitTerminal(coordinator, second.runId());

            assertThat(coordinator.find(first.runId())).isPresent();
            assertThat(coordinator.list()).hasSize(1);

            MvcResult subscribed = mvc.perform(get(
                                    "/api/agent-runs/{runId}/events?afterSeq=0", second.runId())
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            subscribed.getAsyncResult(2_000);
            mvc.perform(asyncDispatch(subscribed))
                    .andExpect(status().isOk())
                    .andExpect(header().string(
                            "Cache-Control", "no-cache, no-transform"))
                    .andExpect(header().string("X-Accel-Buffering", "no"))
                    .andExpect(content().string(containsString("event:run.started")))
                    .andExpect(content().string(containsString("event:message.completed")))
                    .andExpect(content().string(containsString("event:run.completed")))
                    .andExpect(content().string(not(containsString("event:message.delta"))));
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

            var durableNames = coordinator.history("recoverable-1").stream()
                    .map(com.github.agentos.kernel.AgentStreamEvent::event).toList();
            assertThat(durableNames).contains(
                    "run.started", "message.started", "message.completed", "run.completed");
            assertThat(durableNames).doesNotContain("status", "message.delta");

            mvc.perform(get("/api/agent-runs/{runId}", runId))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("\"status\":\"COMPLETED\"")))
                    .andExpect(content().string(containsString("\"output\":\"done\"")));

            MvcResult subscribed = mvc.perform(get(
                                    "/api/agent-runs/{runId}/events?afterSeq=1", runId)
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            subscribed.getAsyncResult(2_000);

            mvc.perform(asyncDispatch(subscribed))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("id:1\n"))))
                    .andExpect(content().string(containsString("id:2")))
                    .andExpect(content().string(containsString("event:message.delta")))
                    .andExpect(content().string(containsString("event:message.completed")))
                    .andExpect(content().string(containsString("event:run.completed")))
                    .andExpect(content().string(containsString("\"lastSeq\":6")));

            MvcResult resumedByHeader = mvc.perform(get(
                                    "/api/agent-runs/{runId}/events?afterSeq=0", runId)
                            .header("Last-Event-ID", "4")
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(request().asyncStarted())
                    .andReturn();
            resumedByHeader.getAsyncResult(2_000);
            mvc.perform(asyncDispatch(resumedByHeader))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("id:4\n"))))
                    .andExpect(content().string(containsString("id:5")))
                    .andExpect(content().string(containsString("id:6")));
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

            AgentRunSnapshot disconnectedRun = coordinator.start(
                    new AgentRequest("disconnect-1", "hello", Map.of()),
                    InvocationContext.of("agent"));
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var emitter = coordinator.stream(disconnectedRun.runId(), 0).orElseThrow();
            emitter.complete();
            firstRelease.countDown();
            AgentRunSnapshot completed = awaitTerminal(
                    coordinator, disconnectedRun.runId());
            assertThat(completed.status()).isEqualTo(AgentRunStatus.COMPLETED);

            AgentRunSnapshot cancellableRun = coordinator.start(
                    new AgentRequest("cancel-1", "hello", Map.of()),
                    InvocationContext.of("agent"));
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            AgentRunCoordinator.CancelResult cancellation = coordinator.cancel(
                    cancellableRun.runId()).orElseThrow();
            assertThat(cancellation.interruptRequested()).isTrue();
            AgentRunSnapshot cancelled = awaitTerminal(
                    coordinator, cancellableRun.runId());
            assertThat(cancelled.status()).isEqualTo(AgentRunStatus.CANCELLED);
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

            AgentRunSnapshot first = coordinator.start(
                    new AgentRequest("list-1", "hello", Map.of()),
                    InvocationContext.of("agent"));
            awaitTerminal(coordinator, first.runId());
            // 创建时间以毫秒记录；显式间隔保证两次运行的排序稳定。
            Thread.sleep(5);
            AgentRunSnapshot second = coordinator.start(
                    new AgentRequest("list-2", "hello", Map.of()),
                    InvocationContext.of("agent"));
            awaitTerminal(coordinator, second.runId());

            mvc.perform(get("/api/agent-runs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].sessionId").value("list-2"))
                    .andExpect(jsonPath("$[1].sessionId").value("list-1"))
                    .andExpect(jsonPath("$[0].status").value("COMPLETED"));
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

    private static AgentRunSnapshot awaitTerminal(
            AgentRunCoordinator coordinator, String runId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            AgentRunSnapshot snapshot = coordinator.find(runId).orElseThrow();
            if (snapshot.status() != AgentRunStatus.CREATED
                    && snapshot.status() != AgentRunStatus.RUNNING) {
                return snapshot;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("run did not reach a terminal state: " + runId);
    }
}
