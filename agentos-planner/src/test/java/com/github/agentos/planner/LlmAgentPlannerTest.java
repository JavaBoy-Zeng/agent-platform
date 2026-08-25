package com.github.agentos.planner;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.memory.CompletedTurn;
import com.github.agentos.memory.MemoryScope;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.builtin.other.EchoTool;
import com.github.agentos.planner.flow.HistoryProcessor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmAgentPlannerTest {

    @Test
    void defaultsMissingOptionalFlagToRequired() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new EchoTool()));

        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> new ModelPlan(
                    PlanType.DISCOVERY,
                    PlanOutcome.CONTINUE,
                    "Continue processing",
                    List.of(new ModelPlan.Step(
                            "step-1",
                            "Echo input",
                            null,
                            "echo",
                            Map.of("message", "hello"))),
                    null);
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 5),
                    new AgentExecutionLimits(3, 30, 30, 6));

            AgentPlan plan = planner.createPlan(
                    AgentRequest.of("session-1", "hello"),
                    InvocationContext.of("main-agent"));

            assertThat(plan.steps()).singleElement().satisfies(step ->
                    assertThat(step.optional()).isFalse());
        }
    }

    @Test
    void promotesDiscoveryPlanWithSideEffectingToolToExecution() {
        AgentTool writeTool = new AgentTool() {
            @Override public String name() { return "write"; }
            @Override public String description() { return "write a resource"; }
            @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return ToolResult.success("written");
            }
        };
        ToolRegistry toolRegistry = new ToolRegistry(List.of(writeTool));

        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> new ModelPlan(
                    PlanType.DISCOVERY,
                    PlanOutcome.CONTINUE,
                    "Write the requested resource",
                    List.of(new ModelPlan.Step(
                            "step-1", "Write it", false, "write", Map.of())),
                    null);
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 5),
                    new AgentExecutionLimits(3, 30, 30, 6));

            AgentPlan plan = planner.createPlan(
                    AgentRequest.of("session-1", "write it"),
                    InvocationContext.of("main-agent"));

            assertThat(plan.type()).isEqualTo(PlanType.EXECUTION);
            assertThat(plan.steps()).singleElement().satisfies(step ->
                    assertThat(step.toolCalls().getFirst().toolName()).isEqualTo("write"));
        }
    }

    @Test
    void recallsMemoryAndInjectsRuntimeOnlyPlanMetadata() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new EchoTool()));
        MemoryScope scope = MemoryScope.defaultScope("main-agent", "session-1");

        try (MemoryService memoryService = MemoryService.inMemory()) {
            memoryService.capture(CompletedTurn.success(
                    scope,
                    "I prefer concise Java answers.",
                    "Understood.",
                    List.of("preference saved")));
            assertThat(memoryService.awaitIdle(Duration.ofSeconds(2))).isTrue();

            AtomicReference<PlanningRequest> capturedRequest = new AtomicReference<>();
            ModelClient modelClient = request -> {
                capturedRequest.set(request);
                return new ModelPlan(
                        PlanType.EXECUTION,
                        PlanOutcome.CONTINUE,
                        "Reply to the user",
                        List.of(new ModelPlan.Step(
                                "step-1",
                                "Echo input",
                                false,
                                "echo",
                                Map.of("message", request.agentRequest().objective()))),
                        null);
            };
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 5),
                    new AgentExecutionLimits(3, 30, 30, 6));
            AgentRequest agentRequest = AgentRequest.of("session-1", "hello");
            InvocationContext context = InvocationContext.of("main-agent");

            AgentPlan plan = planner.createPlan(agentRequest, context);

            assertThat(plan.objective()).isEqualTo("Reply to the user");
            assertThat(plan.origin()).isEqualTo(PlanOrigin.INITIAL);
            assertThat(plan.id()).isNotBlank();
            PlanningRequest request = capturedRequest.get();
            assertThat(request.agentRequest()).isEqualTo(agentRequest);
            assertThat(request.agentContext()).isEqualTo(context);
            assertThat(request.memoryContext().recentTurns()).hasSize(1);
            assertThat(request.memoryContext().formattedContext())
                    .contains("L0 Recent completed turns", "<memory_context>");
            assertThat(request.maxSteps()).isEqualTo(5);
            assertThat(request.availableTools()).singleElement().satisfies(tool ->
                    assertThat(tool.name()).isEqualTo("echo"));
        }
    }

    @Test
    void allowsCompleteReplanWhenStepBudgetIsFullyConsumed() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(new EchoTool()));
        PlanStep currentStep = new PlanStep(
                "step-29", "last step", false,
                new ToolCall("echo", Map.of("message", "done")));
        AgentPlan previousPlan = new AgentPlan(
                "plan-29",
                PlanType.EXECUTION,
                PlanOrigin.REPLANNED,
                PlanOutcome.CONTINUE,
                "execute",
                List.of(currentStep),
                "");
        List<StepResult> results = IntStream.range(0, 30)
                .mapToObj(index -> new StepResult(
                        "plan-" + index,
                        "step-" + index,
                        "echo",
                        StepStatus.COMPLETED,
                        "result-" + index,
                        "",
                        ToolFailureType.NONE,
                        1))
                .toList();
        PlanExecutionSnapshot snapshot = new PlanExecutionSnapshot(
                results,
                new DefaultObservationSummarizer().summarize(results),
                currentStep,
                results.getLast(),
                ReplanReason.EXECUTION_COMPLETED);

        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> {
                assertThat(request.maxSteps()).isZero();
                return new ModelPlan(
                        PlanType.EXECUTION,
                        PlanOutcome.COMPLETE,
                        "finish",
                        null,
                        "final answer");
            };
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 10),
                    new AgentExecutionLimits(3, 30, 30, 6));

            AgentPlan result = planner.replan(
                    AgentRequest.of("session-1", "finish"),
                    InvocationContext.of("main-agent"),
                    previousPlan,
                    snapshot);

            assertThat(result.outcome()).isEqualTo(PlanOutcome.COMPLETE);
            assertThat(result.finalAnswer()).isEqualTo("final answer");
        }
    }

    @Test
    void replacesUngroundedFileCompletionWithDeterministicRead() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(fileReadTool()));
        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> new ModelPlan(
                    PlanType.EXECUTION,
                    PlanOutcome.COMPLETE,
                    "确认简历公司",
                    null,
                    "这些公司都在简历里");
            LlmAgentPlanner planner = planner(modelClient, toolRegistry, memoryService);
            AgentRequest request = new AgentRequest("session-1", "一共待过哪几家公司？", Map.of(
                    HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE,
                    "用户：读取 /tmp/resume.md\n助手：已读取",
                    HistoryProcessor.CONVERSATION_FILE_CONTEXT_ATTRIBUTE, true,
                    HistoryProcessor.REQUIRES_FILE_EVIDENCE_ATTRIBUTE, true));

            AgentPlan plan = planner.createPlan(request, InvocationContext.of("main-agent"));

            assertThat(plan.outcome()).isEqualTo(PlanOutcome.CONTINUE);
            assertThat(plan.steps()).singleElement().satisfies(step -> {
                assertThat(step.toolCall().toolName()).isEqualTo("file_read");
                assertThat(step.toolCall().arguments())
                        .containsEntry("path", "/tmp/resume.md")
                        .containsEntry("offset", 0);
            });
        }
    }

    @Test
    void allowsAccessBoundaryAnswerWithoutCallingFileTool() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(fileReadTool()));
        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> new ModelPlan(
                    PlanType.EXECUTION,
                    PlanOutcome.COMPLETE,
                    "拒绝越界目录访问",
                    null,
                    "无法访问 /Users/whale_fall；允许根目录是 /Users/whale_fall/agentos。");
            LlmAgentPlanner planner = planner(modelClient, toolRegistry, memoryService);

            AgentPlan plan = planner.createPlan(
                    AgentRequest.of("session-1", "列出上级目录中的文件"),
                    InvocationContext.of("main-agent"));

            assertThat(plan.outcome()).isEqualTo(PlanOutcome.COMPLETE);
            assertThat(plan.steps()).isEmpty();
            assertThat(plan.finalAnswer()).contains("无法访问", "允许根目录");
        }
    }

    @Test
    void rejectsExplicitOutsidePathBeforeModelPlanning() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(fileReadTool()));
        AtomicInteger modelCalls = new AtomicInteger();
        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> {
                modelCalls.incrementAndGet();
                throw new AssertionError("model must not be called for an explicit outside path");
            };
            java.nio.file.Path allowedRoot = java.nio.file.Path.of(
                    "/Users/whale_fall/agentos");
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 10),
                    new AgentExecutionLimits(6, 30, 30, 10),
                    allowedRoot);

            AgentPlan plan = planner.createPlan(
                    AgentRequest.of(
                            "session-1",
                            "列出 /Users/whale_fall/agentos 的上级目录 /Users/whale_fall/"),
                    InvocationContext.of("main-agent"));

            assertThat(modelCalls).hasValue(0);
            assertThat(plan.outcome()).isEqualTo(PlanOutcome.COMPLETE);
            assertThat(plan.steps()).isEmpty();
            assertThat(plan.finalAnswer())
                    .contains("/Users/whale_fall", "/Users/whale_fall/agentos", "未调用文件工具");
        }
    }

    @Test
    void replacesPrematureCompleteWithExactFileContinuation() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(fileReadTool()));
        PlanStep currentStep = new PlanStep(
                "read-1", "read first chunk", false,
                new ToolCall("file_read", Map.of("path", "/tmp/resume.md", "offset", 0)));
        AgentPlan previousPlan = continuingPlan(currentStep);
        StepResult firstChunk = new StepResult(
                previousPlan.id(), currentStep.id(), "file_read", StepStatus.COMPLETED,
                fileMetadata("/tmp/resume.md", 0, 3_000, true), "",
                ToolFailureType.NONE, 1);
        PlanExecutionSnapshot snapshot = snapshot(currentStep, firstChunk);

        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> new ModelPlan(
                    PlanType.EXECUTION, PlanOutcome.COMPLETE,
                    "read resume", null, "已全部读取");
            LlmAgentPlanner planner = planner(modelClient, toolRegistry, memoryService);

            AgentPlan plan = planner.replan(
                    AgentRequest.of("session-1", "完整读取 /tmp/resume.md"),
                    InvocationContext.of("main-agent"), previousPlan, snapshot);

            assertThat(plan.outcome()).isEqualTo(PlanOutcome.CONTINUE);
            assertThat(plan.steps()).singleElement().satisfies(step ->
                    assertThat(step.toolCall().arguments())
                            .containsEntry("path", "/tmp/resume.md")
                            .containsEntry("offset", 3_000)
                            .doesNotContainKey("page"));
        }
    }

    @Test
    void allowsGroundedCompletionAfterFinalFileChunk() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(fileReadTool()));
        PlanStep currentStep = new PlanStep(
                "read-final", "read final chunk", false,
                new ToolCall("file_read", Map.of("path", "/tmp/resume.md", "offset", 3_000)));
        AgentPlan previousPlan = continuingPlan(currentStep);
        StepResult finalChunk = new StepResult(
                previousPlan.id(), currentStep.id(), "file_read", StepStatus.COMPLETED,
                fileMetadata("/tmp/resume.md", 3_000, 0, false)
                        + "\n## 重庆并联科技有限公司（2024-11 ~ 至今）",
                "", ToolFailureType.NONE, 1);

        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> new ModelPlan(
                    PlanType.EXECUTION, PlanOutcome.COMPLETE, "answer", null,
                    "1. **重庆并联科技有限公司**（2024-11 ~ 至今）");
            LlmAgentPlanner planner = planner(modelClient, toolRegistry, memoryService);

            AgentPlan plan = planner.replan(
                    new AgentRequest("session-1", "简历里有哪些公司", Map.of(
                            HistoryProcessor.CONVERSATION_FILE_CONTEXT_ATTRIBUTE, true)),
                    InvocationContext.of("main-agent"), previousPlan,
                    snapshot(currentStep, finalChunk));

            assertThat(plan.outcome()).isEqualTo(PlanOutcome.COMPLETE);
            assertThat(plan.finalAnswer()).contains("重庆并联科技有限公司");
        }
    }

    @Test
    void rejectsOrganizationsMissingFromCurrentFileEvidence() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(fileReadTool()));
        PlanStep currentStep = new PlanStep(
                "read-final", "read final chunk", false,
                new ToolCall("file_read", Map.of("path", "/tmp/resume.md", "offset", 0)));
        AgentPlan previousPlan = continuingPlan(currentStep);
        StepResult result = new StepResult(
                previousPlan.id(), currentStep.id(), "file_read", StepStatus.COMPLETED,
                fileMetadata("/tmp/resume.md", 0, 0, false)
                        + "\n## 重庆并联科技有限公司",
                "", ToolFailureType.NONE, 1);

        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> new ModelPlan(
                    PlanType.EXECUTION, PlanOutcome.COMPLETE, "answer", null,
                    "1. **上海联恩电子有限公司**（2015.05 ~ 2016.09）");
            LlmAgentPlanner planner = planner(modelClient, toolRegistry, memoryService);

            assertThatThrownBy(() -> planner.replan(
                    new AgentRequest("session-1", "简历里有哪些公司", Map.of(
                            HistoryProcessor.CONVERSATION_FILE_CONTEXT_ATTRIBUTE, true)),
                    InvocationContext.of("main-agent"), previousPlan,
                    snapshot(currentStep, result)))
                    .isInstanceOf(PlanValidationException.class)
                    .hasMessageContaining("上海联恩电子有限公司");
        }
    }

    @Test
    void doesNotTreatWebUrlAsAnAbsoluteFilePath() {
        ToolRegistry toolRegistry = new ToolRegistry(List.of(fileReadTool()));
        AtomicInteger modelCalls = new AtomicInteger();
        try (MemoryService memoryService = MemoryService.inMemory()) {
            ModelClient modelClient = request -> {
                modelCalls.incrementAndGet();
                return new ModelPlan(
                        PlanType.EXECUTION, PlanOutcome.COMPLETE,
                        "打开网页", null, "可以使用网页工具处理该地址。");
            };
            LlmAgentPlanner planner = new LlmAgentPlanner(
                    modelClient,
                    toolRegistry,
                    memoryService,
                    new PlanValidator(toolRegistry, 10),
                    new AgentExecutionLimits(6, 30, 30, 10),
                    java.nio.file.Path.of("/Users/whale_fall/agentos"));

            AgentPlan plan = planner.createPlan(
                    AgentRequest.of("session-1", "打开 https://example.com/docs"),
                    InvocationContext.of("main-agent"));

            assertThat(modelCalls).hasValue(1);
            assertThat(plan.outcome()).isEqualTo(PlanOutcome.COMPLETE);
        }
    }

    private static LlmAgentPlanner planner(
            ModelClient modelClient, ToolRegistry registry, MemoryService memoryService) {
        return new LlmAgentPlanner(
                modelClient,
                registry,
                memoryService,
                new PlanValidator(registry, 10),
                new AgentExecutionLimits(6, 30, 30, 10));
    }

    private static AgentTool fileReadTool() {
        return new AgentTool() {
            @Override public String name() { return "file_read"; }
            @Override public String description() { return "read a file with continuation metadata"; }
            @Override public List<ToolParameter> parameters() {
                return List.of(
                        new ToolParameter("path", ToolParameter.ValueType.STRING, "path", true),
                        new ToolParameter("page", ToolParameter.ValueType.INTEGER, "page", false),
                        new ToolParameter("offset", ToolParameter.ValueType.INTEGER, "offset", false));
            }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return ToolResult.success("unused");
            }
        };
    }

    private static AgentPlan continuingPlan(PlanStep step) {
        return new AgentPlan(
                "plan-file", PlanType.DISCOVERY, PlanOrigin.INITIAL,
                PlanOutcome.CONTINUE, "read file", List.of(step), "");
    }

    private static PlanExecutionSnapshot snapshot(PlanStep step, StepResult result) {
        return new PlanExecutionSnapshot(
                List.of(result),
                new DefaultObservationSummarizer().summarize(List.of(result)),
                step, result, ReplanReason.EXECUTION_COMPLETED);
    }

    private static String fileMetadata(
            String path, int offset, int nextOffset, boolean hasMore) {
        return """
                [file_read_metadata]
                format=linear
                path=%s
                page=0
                totalPages=0
                offset=%d
                returnedChars=100
                totalChars=3100
                hasMore=%s
                nextPage=0
                nextOffset=%d
                truncated=%s
                [/file_read_metadata]
                [content]
                chunk
                """.formatted(path, offset, hasMore, nextOffset, hasMore);
    }
}
