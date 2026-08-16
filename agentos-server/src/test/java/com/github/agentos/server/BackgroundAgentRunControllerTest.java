package com.github.agentos.server;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentLoop;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRuntime;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.server.controller.BackgroundAgentRunController;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 后台运行快照、断点补播和显式取消的接口测试。 */
class BackgroundAgentRunControllerTest {

    @Test
    void replaysOnlyEventsAfterCursorAndReturnsFinalSnapshot() throws Exception {
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
                    new AgentRuntime(loop), executor, new AgentRunTaskRegistry());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new BackgroundAgentRunController(coordinator)).build();

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
                    .andExpect(content().string(containsString("event:output_delta")))
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
                    new AgentRuntime(loop), executor, new AgentRunTaskRegistry());

            AgentRunCoordinator.RunSnapshot disconnectedRun = coordinator.start(
                    new AgentRequest("disconnect-1", "hello", Map.of()),
                    AgentContext.of("agent"));
            assertThat(firstStarted.await(2, TimeUnit.SECONDS)).isTrue();
            var emitter = coordinator.stream(disconnectedRun.runId(), 0).orElseThrow();
            emitter.complete();
            firstRelease.countDown();
            AgentRunCoordinator.RunSnapshot completed = awaitTerminal(
                    coordinator, disconnectedRun.runId());
            assertThat(completed.state().status()).isEqualTo(AgentState.Status.COMPLETED);

            AgentRunCoordinator.RunSnapshot cancellableRun = coordinator.start(
                    new AgentRequest("cancel-1", "hello", Map.of()),
                    AgentContext.of("agent"));
            assertThat(secondStarted.await(2, TimeUnit.SECONDS)).isTrue();
            AgentRunCoordinator.CancelResult cancellation = coordinator.cancel(
                    cancellableRun.runId()).orElseThrow();
            assertThat(cancellation.interruptRequested()).isTrue();
            AgentRunCoordinator.RunSnapshot cancelled = awaitTerminal(
                    coordinator, cancellableRun.runId());
            assertThat(cancelled.state().status()).isEqualTo(AgentState.Status.CANCELLED);
        }
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
