package com.github.agentos.agent.specialist;

import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.routing.*;
import com.github.agentos.agent.workflow.ResumableSpecialist;
import com.github.agentos.kernel.*;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.planner.flow.*;
import com.github.agentos.tool.api.*;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 按业务能力组合 ToolProvider 的通用专才，保留原生参数、审批和可恢复执行。 */
public final class ToolProviderAgent extends ResumableSpecialist implements RoutableAgent {
    private final ChatClient chat;
    private final ToolProvider provider;
    private final String instruction;
    private final Set<AgentCapability> capabilities;
    private final Set<RouteScope> scopes;
    private final com.github.agentos.agent.loop.ConversationCompactor compactor =
            new com.github.agentos.agent.loop.ConversationCompactor(16_000, 6_000, 1_000);
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolProviderAgent(String id, String description, String instruction,
            Set<AgentCapability> capabilities, Set<RouteScope> scopes,
            ToolProvider provider, ChatClient chat) {
        super(id, description);
        this.instruction = Objects.requireNonNull(instruction);
        this.capabilities = Set.copyOf(capabilities);
        this.scopes = Set.copyOf(scopes);
        this.provider = Objects.requireNonNull(provider);
        this.chat = Objects.requireNonNull(chat);
    }

    @Override public Set<AgentCapability> capabilities() { return capabilities; }
    @Override public RouteAcceptance accepts(AgentRequest request, SupervisorRouteDecision decision) {
        return scopes.contains(decision.scope()) ? RoutableAgent.super.accepts(request, decision)
                : RouteAcceptance.reject("scope is outside this tool group");
    }
    @Override public AgentExecutionResult run(AgentRequest request, InvocationContext context) {
        return AgentExecutionResult.from(run(request, context,
                AgentState.ready().startNextIteration(), AgentEventSink.NOOP),
                context.invocation() == null ? null : context.invocation().pendingAction());
    }

    @Override protected AgentState runWorkflow(AgentRequest request, InvocationContext context,
            AgentState state, AgentEventSink sink) {
        List<AgentTool> tools = provider.getTools(context).stream()
                .filter(tool -> !(tool instanceof AgentDelegationTool)).toList();
        var definitions = tools.stream().map(tool -> new LlmToolDefinition(
                tool.name(), tool.description(), tool.parametersSchema())).toList();
        var seeded = new HistoryProcessor().process(new LlmRequest(instruction,
                List.of(LlmMessage.user(request.objective()))), request);
        List<LlmMessage> messages = new ArrayList<>(seeded.messages());
        Map<String, String> fileEvidence = new LinkedHashMap<>();
        Deque<String> observations = new ArrayDeque<>();
        try {
            for (int round = 0; round < 30; round++) {
                context.throwIfCancelled();
                var input = new LlmRequest(instruction + "\n只调用本次提供的工具。不得猜测工具结果；"
                        + "文件读取有续读游标时先读完再总结。工具失败应明确说明。",
                        List.copyOf(messages), "", definitions).withRouting(request);
                sink.emit(AgentRunEvent.of(AgentRunEvent.Type.DECISION, request.sessionId(),
                        "正在分析工具观察，第 " + (round + 1) + " 轮", Map.of("agentId", id())));
                String raw = modelResult("round-" + round, context,
                        () -> mapper.writeValueAsString(chat.chatWithTools(request.sessionId(), input, delta -> { })));
                var response = mapper.readValue(raw, ChatClient.ToolCallResponse.class);
                if (response.toolCall() == null) {
                    String answer = response.answer();
                    if (answer == null || answer.isBlank()) return state.fail("empty tool group answer");
                    // 保留文件读取的证据元数据，供父规划器验证，不能只交一段无来源结论。
                    return state.complete(bounded(answer, 4_000) + "\n\n" + String.join("\n\n", fileEvidence.values()));
                }
                ToolCall call = response.toolCall();
                AgentTool selected = tools.stream().filter(tool -> tool.name().equals(call.toolName()))
                        .findFirst().orElse(null);
                if (selected == null) return state.fail("tool is outside this agent's capability: " + call.toolName());
                sink.emit(AgentRunEvent.of(AgentRunEvent.Type.TOOL_STARTED, request.sessionId(),
                        "执行 " + call.toolName(), Map.of("agentId", id(), "toolName", call.toolName(), "stepId", "round-" + round)));
                ToolResult result = dispatchTool(selected, call, request, context);
                sink.emit(AgentRunEvent.of(AgentRunEvent.Type.TOOL_FINISHED, request.sessionId(),
                        call.toolName() + (result.success() ? " 执行完成" : " 执行失败"),
                        Map.of("agentId", id(), "toolName", call.toolName(), "stepId", "round-" + round,
                                "status", result.success() ? "COMPLETED" : "FAILED")));
                String callId = response.toolCallId() == null ? "group-" + round : response.toolCallId();
                messages.add(LlmMessage.assistantToolCallWithReasoning("(工具调用)", response.reasoningContent(),
                        new LlmMessage.ToolCallPart(callId, call.toolName(), mapper.writeValueAsString(call.arguments()))));
                String output = result.success() ? result.output() : result.error();
                if (result.success() && call.toolName().startsWith("file_")) {
                    // 保留文件游标证据，不把读过的文件全文再次传给父模型。
                    var matcher = java.util.regex.Pattern.compile(
                            "\\[file_read_metadata]\\R(.*?)\\R\\[/file_read_metadata]",
                            java.util.regex.Pattern.DOTALL).matcher(output);
                    while (matcher.find()) {
                        String block = matcher.group();
                        String path = block.lines().filter(line -> line.startsWith("path="))
                                .findFirst().orElse(block);
                        // 同一路径只保留最新游标，避免已读完却把旧 hasMore=true 交回父级。
                        fileEvidence.remove(path);
                        fileEvidence.put(path, block);
                        while (fileEvidence.values().stream().mapToInt(String::length).sum() > 2_000) {
                            fileEvidence.remove(fileEvidence.keySet().iterator().next());
                        }
                    }
                }
                String observation = call.toolName() + " " + mapper.writeValueAsString(call.arguments())
                        + "\n" + (result.success() ? "" : "FAILED: ") + bounded(output, 1_500);
                observations.addLast(bounded(observation, 1_800));
                if (observations.size() > 3) observations.removeFirst();
                messages.add(LlmMessage.toolResult(callId, compactor.wrapToolResult(call.toolName(), output)));
                messages = new ArrayList<>(compactor.compact(messages).messages());
            }
            return partial(state, "tool group round limit exhausted", observations);
        } catch (BudgetExhausted exhausted) {
            return partial(state, exhausted.getMessage(), observations);
        }
    }
    private static AgentState partial(AgentState state, String reason, Deque<String> observations) {
        return new AgentState(AgentState.Status.FAILED, state.iteration(),
                String.join("\n\n", observations),
                reason + "；子任务未完成。以下保留最近最多3条观察，长输出仅保留首尾。请依据已有观察继续，不要重新执行已完成的步骤。",
                java.time.Instant.now());
    }

    private static String bounded(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, limit / 2) + "\n…(中间已截断)\n"
                + text.substring(text.length() - limit / 2);
    }
}
