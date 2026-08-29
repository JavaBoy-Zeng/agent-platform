package com.github.agentos.agent.loop;

import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunStatus;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.ExecutionCounters;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.PendingAction;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.kernel.PendingActionType;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.LlmMessage;
import com.github.agentos.planner.flow.LlmRequest;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;


import static org.assertj.core.api.Assertions.assertThat;

class ReactAgentTest {

    @Test
    void resumesFromApprovalCheckpointAndPreservesAccumulatedContext() {
        // 场景：第一轮模型请求调用 dangerous_write → 工具返回 pendingAction
        // → ReactAgent 保存 continuation 并进入 WAITING → resume(approved=true)
        // → 工具真正执行，结果作为 TOOL 观察追加 → 第二轮模型给出最终回答。
        List<LlmMessage> messagesSeenOnResume = new ArrayList<>();
        AtomicInteger toolExecutions = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean approved =
                new java.util.concurrent.atomic.AtomicBoolean();
        PendingAction approval = new PendingAction(
                "pa-1", PendingActionType.HUMAN_APPROVAL,
                "审批写入", "请批准写入操作", Map.of());
        ChatClient chatClient = nativeToolCallClient((request, round) -> {
            request.messages().stream()
                    .filter(message -> message.role() == LlmMessage.Role.TOOL)
                    .forEach(messagesSeenOnResume::add);
            if (round == 1) {
                return ChatClient.ToolCallResponse.call(
                        new ToolCall("dangerous_write", Map.of("path", "/tmp/x")),
                        "call-pa-1", null);
            }
            return ChatClient.ToolCallResponse.answer("写入完成，这是最终回答", null);
        }, deltas -> { });
        AgentTool dangerousWrite = new AgentTool() {
            @Override
            public String name() {
                return "dangerous_write";
            }
            @Override
            public String description() {
                return "需要审批的写入工具";
            }
            @Override
            public ToolResult execute(ToolContext context, ToolCall call) {
                if (!approved.get()) {
                    return ToolResult.pending(approval);
                }
                toolExecutions.incrementAndGet();
                return ToolResult.success("已写入 " + call.arguments().get("path"));
            }
        };
        ReactAgent agent = reactAgent(chatClient, dangerousWrite,
                AgentExecutionLimits.defaults(), ContinuationStore.NOOP);
        List<AgentRunEvent> events = new ArrayList<>();
        InvocationContext context = InvocationContext.of(ReactAgent.ID)
                .withRuntime(invocationForResume(), com.github.agentos.kernel.AgentEventPublisher.NOOP);

        // 第一次 run：工具返回 pendingAction，进入 WAITING。
        AgentState waiting = agent.run(
                AgentRequest.of("session-pa", "写入 /tmp/x"),
                context, AgentState.ready().startNextIteration(), events::add);
        assertThat(waiting.status()).isEqualTo(AgentState.Status.WAITING);
        assertThat(events).extracting(AgentRunEvent::type)
                .contains(AgentRunEvent.Type.TOOL_STARTED);
        // continuation 已保存（事件中 continuationSaved=true）。
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.DECISION
                        && "WAITING".equals(event.data().get("outcome")))
                .extracting(event -> event.data().get("continuationSaved"))
                .contains(true);
        assertThat(toolExecutions).hasValue(0);

        // 审批通过后 resume：翻 approved 标志，从断点恢复。
        approved.set(true);
        AgentCheckpoint checkpoint = new AgentCheckpoint(
                "session-pa", context.invocationId(), ReactAgent.ID,
                "task-1", "team-1", "user-1", "写入 /tmp/x",
                "", "", 0, List.of(), Map.of(),
                approval, new ExecutionCounters(0, 0, 0, 0),
                AgentRunStatus.WAITING, java.time.Instant.now());
        List<AgentRunEvent> resumeEvents = new ArrayList<>();
        AgentState completed = agent.resume(
                AgentRequest.of("session-pa", "写入 /tmp/x"),
                context,
                AgentState.ready().startNextIteration(),
                checkpoint,
                PendingActionResolution.approved("pa-1"),
                resumeEvents::add);

        // 审批后工具真正执行了一次，模型在第二轮看到 TOOL 观察并给出最终回答。
        assertThat(completed.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(completed.output()).isEqualTo("写入完成，这是最终回答");
        assertThat(toolExecutions).hasValue(1);
        assertThat(messagesSeenOnResume).isNotEmpty();
        // resume 事件流包含工具执行与观察。
        assertThat(resumeEvents).extracting(AgentRunEvent::type).contains(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION,
                AgentRunEvent.Type.RUN_COMPLETED);
    }

    /** 构造一个支持 resume 路径的最小 AgentInvocation。 */
    private static com.github.agentos.kernel.AgentInvocation invocationForResume() {
        return new com.github.agentos.kernel.AgentInvocation(
                "inv-resume-test", "session-pa", ReactAgent.ID, "", java.time.Instant.now());
    }

    @Test
    void executesToolObservesResultThenCompletesWithoutPlan() {
        List<String> receivedToolObservations = new ArrayList<>();
        List<String> receivedToolCallIds = new ArrayList<>();
        AtomicInteger toolExecutions = new AtomicInteger();
        List<String> callArguments = new ArrayList<>();
        ChatClient chatClient = nativeToolCallClient((request, round) -> {
            request.messages().stream()
                    .filter(message -> message.role() == LlmMessage.Role.TOOL)
                    .map(LlmMessage::content)
                    .reduce((first, second) -> second)
                    .ifPresent(receivedToolObservations::add);
            request.messages().stream()
                    .filter(message -> message.role() == LlmMessage.Role.TOOL)
                    .map(LlmMessage::toolCallId)
                    .forEach(receivedToolCallIds::add);
            if (round == 1) {
                return ChatClient.ToolCallResponse.call(
                        new ToolCall("echo", Map.of("text", "你好")), "call-abc", null);
            }
            return ChatClient.ToolCallResponse.answer("工具已执行，这是最终回答", null);
        }, deltas -> { });
        AgentTool echo = tool("echo", call -> {
            toolExecutions.incrementAndGet();
            callArguments.add(String.valueOf(call.arguments().get("text")));
            return ToolResult.success("回声：" + call.arguments().get("text"));
        });
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = reactAgent(chatClient, echo, AgentExecutionLimits.defaults())
                .run(AgentRequest.of("session-1", "把这句话回显一下"),
                        InvocationContext.of(ReactAgent.ID),
                        AgentState.ready().startNextIteration(),
                        events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("工具已执行，这是最终回答");
        assertThat(toolExecutions).hasValue(1);
        assertThat(callArguments).containsExactly("你好");
        // 第二轮模型请求必须看到第一轮的真实工具观察（观察-行动反馈闭环），
        // 且 TOOL 结果按 toolCallId 与 assistant 工具调用配对（原生协议）。
        assertThat(receivedToolObservations).hasSize(1);
        assertThat(receivedToolObservations.getFirst()).startsWith("[tool] echo")
                .contains("回声：你好");
        assertThat(receivedToolCallIds).containsExactly("call-abc");
        // 核心断言：无显式计划，事件流不出现 PLAN_CREATED / REPLAN。
        assertThat(events).extracting(AgentRunEvent::type)
                .doesNotContain(AgentRunEvent.Type.PLAN_CREATED, AgentRunEvent.Type.REPLAN);
        assertThat(events).extracting(AgentRunEvent::type).containsSequence(
                AgentRunEvent.Type.RUN_STARTED,
                AgentRunEvent.Type.DECISION,
                AgentRunEvent.Type.TOOL_STARTED,
                AgentRunEvent.Type.TOOL_FINISHED,
                AgentRunEvent.Type.OBSERVATION);
        assertThat(events.getLast().type()).isEqualTo(AgentRunEvent.Type.RUN_COMPLETED);
        assertThat(events).filteredOn(event -> event.type() == AgentRunEvent.Type.DECISION)
                .extracting(event -> event.data().get("reason"))
                .contains("TOOL_SELECTED");
    }

    @Test
    void unknownToolBecomesFailureObservationInsteadOfHardFail() {
        List<String> observations = new ArrayList<>();
        ChatClient chatClient = nativeToolCallClient((request, round) -> {
            request.messages().stream()
                    .filter(message -> message.role() == LlmMessage.Role.TOOL)
                    .map(LlmMessage::content)
                    .forEach(observations::add);
            if (round == 1) {
                return ChatClient.ToolCallResponse.call(
                        new ToolCall("missing_tool", Map.of()), "call-1", null);
            }
            return ChatClient.ToolCallResponse.answer("工具不存在，直接给出最终回答", null);
        }, deltas -> { });
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = reactAgent(chatClient, tool("echo", call -> ToolResult.success("ok")),
                AgentExecutionLimits.defaults())
                .run(AgentRequest.of("session-1", "测试未知工具"),
                        InvocationContext.of(ReactAgent.ID),
                        AgentState.ready().startNextIteration(),
                        events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(observations).hasSize(1);
        assertThat(observations.getFirst()).contains("missing_tool").contains("执行失败");
    }

    @Test
    void failsWhenModelCallBudgetExhausted() {
        ChatClient chatClient = nativeToolCallClient((request, round) ->
                ChatClient.ToolCallResponse.call(
                        new ToolCall("echo", Map.of()), "call-" + round, null),
                deltas -> { });
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = reactAgent(chatClient, tool("echo", call -> ToolResult.success("ok")),
                new AgentExecutionLimits(3, 30, 30, 1))
                .run(AgentRequest.of("session-1", "预算耗尽场景"),
                        InvocationContext.of(ReactAgent.ID),
                        AgentState.ready().startNextIteration(),
                        events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("maxModelCalls");
    }

    @Test
    void finalAnswerStreamsLiveAndRequestCarriesToolDefinitions() {
        List<LlmRequest> seenRequests = new ArrayList<>();
        ChatClient chatClient = nativeToolCallClient((request, round) -> {
            seenRequests.add(request);
            if (round == 1) {
                return ChatClient.ToolCallResponse.call(
                        new ToolCall("echo", Map.of()), "call-1", null);
            }
            return ChatClient.ToolCallResponse.answer("这是流式最终回答", null);
        }, deltas -> {
            deltas.accept("这是");
            deltas.accept("流式最终回答");
        });
        List<AgentRunEvent> events = new ArrayList<>();

        AgentState result = reactAgent(chatClient, tool("echo", call -> ToolResult.success("ok")),
                AgentExecutionLimits.defaults())
                .run(AgentRequest.of("session-1", "流式回答"),
                        InvocationContext.of(ReactAgent.ID),
                        AgentState.ready().startNextIteration(),
                        events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("这是流式最终回答");
        // 原生协议：每轮请求都携带 JSON Schema 工具定义。
        assertThat(seenRequests).hasSize(2);
        assertThat(seenRequests.getFirst().tools()).hasSize(1);
        assertThat(seenRequests.getFirst().tools().getFirst().name()).isEqualTo("echo");
        assertThat(seenRequests.getFirst().tools().getFirst().parametersSchema())
                .containsEntry("type", "object");
        // 仅最终回答轮转发正文增量。
        List<AgentRunEvent> outputDeltas = events.stream()
                .filter(event -> event.type() == AgentRunEvent.Type.OUTPUT_DELTA)
                .toList();
        assertThat(outputDeltas).extracting(AgentRunEvent::message)
                .containsExactly("这是", "流式最终回答");
        assertThat(outputDeltas).extracting(event -> event.data().get("source"))
                .containsOnly("model-sse");
    }

    /**
     * 构造按轮次脚本化的原生 function calling 客户端。
     *
     * @param script （请求、轮次从 1 开始）→ 结构化工具调用或最终回答
     * @param deltas 最终回答的增量回调（工具调用轮不会触发）
     */
    private static ChatClient nativeToolCallClient(
            java.util.function.BiFunction<LlmRequest, Integer, ChatClient.ToolCallResponse> script,
            Consumer<Consumer<String>> deltas) {
        AtomicInteger round = new AtomicInteger();
        return new ChatClient() {
            @Override
            public String chat(String sessionId, LlmRequest request) {
                throw new UnsupportedOperationException("chatWithTools path is expected");
            }

            @Override
            public ToolCallResponse chatWithTools(
                    String sessionId, LlmRequest request, Consumer<String> onDelta) {
                ChatClient.ToolCallResponse response =
                        script.apply(request, round.incrementAndGet());
                if (response.toolCall() == null) {
                    deltas.accept(onDelta);
                }
                return response;
            }
        };
    }

    private ReactAgent reactAgent(
            ChatClient chatClient,
            AgentTool registeredTool,
            AgentExecutionLimits limits) {
        return reactAgent(chatClient, registeredTool, limits, ContinuationStore.NOOP);
    }

    private ReactAgent reactAgent(
            ChatClient chatClient,
            AgentTool registeredTool,
            AgentExecutionLimits limits,
            ContinuationStore continuationStore) {
        return reactAgent(chatClient, registeredTool, limits, continuationStore, 0, 0);
    }

    private ReactAgent reactAgent(
            ChatClient chatClient,
            AgentTool registeredTool,
            AgentExecutionLimits limits,
            ContinuationStore continuationStore,
            int reflectionInterval,
            int consecutiveFailureThreshold) {
        List<AgentTool> tools = List.of(registeredTool);
        return new ReactAgent(
                chatClient,
                new ToolDispatcher(new ToolRegistry(tools)),
                tools,
                limits,
                new ConversationCompactor(1_000, 400, 60),
                continuationStore,
                reflectionInterval,
                consecutiveFailureThreshold);
    }

    @Test
    void periodicReflectionTriggersAndAppendsToMessages() {
        // 场景：reflectionInterval=2，工具调用到第 2、4 次时触发反思；
        // 反思返回 continue=true，继续执行直到最终回答。
        AtomicInteger reflectionCalls = new AtomicInteger();
        AtomicInteger decisionCalls = new AtomicInteger();
        // 主决策：前 4 轮调工具，第 5 轮给最终回答。
        // 反思：返回 continue=true。
        ChatClient chatClient = new ChatClient() {
            @Override
            public String chat(String sessionId, LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ToolCallResponse chatWithTools(
                    String sessionId, LlmRequest request, Consumer<String> onDelta) {
                // 反思请求的 systemInstruction 含 "[反思阶段]"。
                if (request.systemInstruction() != null
                        && request.systemInstruction().contains("[反思阶段]")) {
                    reflectionCalls.incrementAndGet();
                    return ToolCallResponse.answer(
                            "{\"continue\":true,\"reason\":\"进度正常\"}", null);
                }
                int round = decisionCalls.incrementAndGet();
                if (round <= 4) {
                    return ToolCallResponse.call(
                            new ToolCall("echo", Map.of()), "call-" + round, null);
                }
                onDelta.accept("最终回答");
                return ToolCallResponse.answer("最终回答", null);
            }
        };
        List<AgentRunEvent> events = new ArrayList<>();
        ReactAgent agent = reactAgent(chatClient, tool("echo", call -> ToolResult.success("ok")),
                new AgentExecutionLimits(10, 30, 10, 10),
                ContinuationStore.NOOP,
                2,  // reflectionInterval
                5); // consecutiveFailureThreshold（不触发）
        InvocationContext context = InvocationContext.of(ReactAgent.ID)
                .withRuntime(invocationForResume(), com.github.agentos.kernel.AgentEventPublisher.NOOP);

        AgentState result = agent.run(
                AgentRequest.of("session-reflect", "测试反思"),
                context, AgentState.ready().startNextIteration(), events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        // 4 次工具调用，每 2 次触发反思 → 2 次反思。
        assertThat(reflectionCalls).hasValue(2);
        // 事件流包含 REFLECT 决策与反思 OBSERVATION。
        assertThat(events).filteredOn(e -> e.type() == AgentRunEvent.Type.DECISION
                        && "REFLECT".equals(e.data().get("outcome")))
                .hasSize(2);
        assertThat(events).filteredOn(e -> e.type() == AgentRunEvent.Type.OBSERVATION
                        && Boolean.TRUE.equals(e.data().get("reflection")))
                .hasSize(2);
    }

    @Test
    void consecutiveFailureTriggersReflectionAndAbortsWhenModelSaysStop() {
        // 场景：工具连续失败 2 次触发反思，反思返回 continue=false → 任务中止。
        AtomicInteger reflectionCalls = new AtomicInteger();
        AtomicInteger decisionCalls = new AtomicInteger();
        ChatClient chatClient = new ChatClient() {
            @Override
            public String chat(String sessionId, LlmRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ToolCallResponse chatWithTools(
                    String sessionId, LlmRequest request, Consumer<String> onDelta) {
                if (request.systemInstruction() != null
                        && request.systemInstruction().contains("[反思阶段]")) {
                    reflectionCalls.incrementAndGet();
                    return ToolCallResponse.answer(
                            "{\"continue\":false,\"reason\":\"工具反复失败，无法继续\"}", null);
                }
                decisionCalls.incrementAndGet();
                return ToolCallResponse.call(
                        new ToolCall("fail_tool", Map.of()), "call-" + decisionCalls, null);
            }
        };
        List<AgentRunEvent> events = new ArrayList<>();
        ReactAgent agent = reactAgent(chatClient,
                tool("fail_tool", call -> ToolResult.failure(
                        com.github.agentos.tool.api.ToolFailureType.UNKNOWN, "boom")),
                new AgentExecutionLimits(10, 30, 10, 10),
                ContinuationStore.NOOP,
                10, // reflectionInterval（不触发周期反思）
                2); // consecutiveFailureThreshold
        InvocationContext context = InvocationContext.of(ReactAgent.ID)
                .withRuntime(invocationForResume(), com.github.agentos.kernel.AgentEventPublisher.NOOP);

        AgentState result = agent.run(
                AgentRequest.of("session-fail-reflect", "测试失败反思"),
                context, AgentState.ready().startNextIteration(), events::add);

        // 反思判定中止 → FAILED。
        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("工具反复失败");
        assertThat(reflectionCalls).hasValue(1);
    }

    private static AgentTool tool(String name, Function<ToolCall, ToolResult> behavior) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name + " test tool";
            }

            @Override
            public ToolResult execute(ToolContext context, ToolCall call) {
                return behavior.apply(call);
            }
        };
    }
}
