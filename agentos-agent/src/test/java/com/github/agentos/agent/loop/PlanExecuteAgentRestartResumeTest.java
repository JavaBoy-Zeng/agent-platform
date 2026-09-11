package com.github.agentos.agent.loop;

import com.github.agentos.agent.finalize.DefaultAgentFinalizer;
import com.github.agentos.hitl.ApprovalService;
import com.github.agentos.hitl.ApprovalToolInterceptor;
import com.github.agentos.hitl.RiskPolicy;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.CheckpointStore;
import com.github.agentos.kernel.InMemoryCheckpointStore;
import com.github.agentos.kernel.PendingActionResolution;
import com.github.agentos.memory.MemoryService;
import com.github.agentos.planner.AgentDecision;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.DefaultFailureClassifier;
import com.github.agentos.planner.PlanExecutionSnapshot;
import com.github.agentos.planner.PlanExecutor;
import com.github.agentos.planner.PlanOrigin;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanStep;
import com.github.agentos.planner.PlanType;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模拟进程重启后的审批恢复：新 PlanExecuteAgent + 新 AgentRunner，
 * 只有 CheckpointStore 与 ContinuationStore 跨“重启”共享。
 */
class PlanExecuteAgentRestartResumeTest {

    /** 跨实例共享的内存版续跑存储，模拟 SQLite 持久化。 */
    private static final class SharedContinuationStore implements ContinuationStore {
        private final ConcurrentMap<String, PersistedContinuation> storage =
                new ConcurrentHashMap<>();

        @Override
        public void save(String invocationId, PersistedContinuation continuation) {
            storage.put(invocationId, continuation);
        }

        @Override
        public Optional<PersistedContinuation> load(String invocationId) {
            return Optional.ofNullable(storage.get(invocationId));
        }

        @Override
        public void delete(String invocationId) {
            storage.remove(invocationId);
        }
    }

    @Test
    void resumesApprovedInvocationAfterSimulatedRestart() {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();
        AgentTool writeTool = new AgentTool() {
            @Override public String name() { return "file_write"; }
            @Override public String description() { return "write a file"; }
            @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                writes.incrementAndGet();
                return ToolResult.success("written " + call.arguments().get("path"));
            }
        };
        AgentTool commitTool = new AgentTool() {
            @Override public String name() { return "git_commit"; }
            @Override public String description() { return "commit selected files"; }
            @Override public RiskLevel riskLevel() { return RiskLevel.HIGH; }
            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                commits.incrementAndGet();
                return ToolResult.success("committed");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(writeTool, commitTool));
        PlanExecutor executor = new PlanExecutor(
                new ToolDispatcher(
                        registry,
                        List.of(new ApprovalToolInterceptor(
                                new RiskPolicy(AgentTool.RiskLevel.HIGH),
                                new ApprovalService(request -> false)))),
                new DefaultFailureClassifier());
        AgentPlanner planner = new AgentPlanner() {
            @Override public AgentPlan createPlan(AgentRequest request, InvocationContext context) {
                return AgentPlan.create(
                        PlanType.EXECUTION, PlanOrigin.INITIAL, PlanOutcome.CONTINUE,
                        "write requested file",
                        List.of(
                                new PlanStep(
                                        "write-step", "write file", false,
                                        new ToolCall("file_write", Map.of(
                                                "path", "docs/FEATURES.md"))),
                                new PlanStep(
                                        "commit-step", "commit file", false,
                                        new ToolCall("git_commit", Map.of(
                                                "paths", List.of("docs/FEATURES.md"),
                                                "message", "docs: add features")))),
                        "");
            }

            @Override public AgentPlan replan(
                    AgentRequest request, InvocationContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                return decide(request, context, previousPlan, snapshot).plan();
            }

            @Override public AgentDecision decide(
                    AgentRequest request, InvocationContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                return AgentDecision.from(AgentPlan.create(
                        PlanType.EXECUTION, PlanOrigin.REPLANNED, PlanOutcome.COMPLETE,
                        "file written", List.of(), "文件已写入并提交。"));
            }
        };

        CheckpointStore checkpointStore = new InMemoryCheckpointStore();
        SharedContinuationStore continuationStore = new SharedContinuationStore();

        // 第一次进程：任务挂起等待审批。
        String invocationId;
        try (MemoryService memory = MemoryService.inMemory()) {
            PlanExecuteAgent firstAgent = new PlanExecuteAgent(
                    planner, executor, memory, new DefaultAgentFinalizer(),
                    new AgentExecutionLimits(3, 10, 10, 5),
                    new com.github.agentos.planner.DefaultObservationSummarizer(),
                    continuationStore);
            AgentRunner firstRuntime = new AgentRunner(firstAgent,
                    com.github.agentos.kernel.AgentEventPublisher.NOOP, checkpointStore);
            AgentState waiting = firstRuntime.run(
                    AgentRequest.of("session-restart", "write and commit file"),
                    InvocationContext.of("plan-execute-agent"));
            invocationId = firstRuntime.latestInvocation("session-restart")
                    .orElseThrow().invocationId();

            assertThat(waiting.status()).isEqualTo(AgentState.Status.WAITING);
            assertThat(writes).hasValue(0);
        }

        // 模拟重启：全新 PlanExecuteAgent 与 AgentRunner，仅两个 Store 保留数据。
        String pendingActionId = checkpointStore.load(invocationId)
                .orElseThrow().pendingAction().pendingActionId();
        assertThat(continuationStore.load(invocationId)).isPresent();

        try (MemoryService memory = MemoryService.inMemory()) {
            PlanExecuteAgent secondAgent = new PlanExecuteAgent(
                    planner, executor, memory, new DefaultAgentFinalizer(),
                    new AgentExecutionLimits(3, 10, 10, 5),
                    new com.github.agentos.planner.DefaultObservationSummarizer(),
                    continuationStore);
            AgentRunner secondRuntime = new AgentRunner(secondAgent,
                    com.github.agentos.kernel.AgentEventPublisher.NOOP, checkpointStore);

            AgentState resumed = secondRuntime.resume(
                    invocationId, PendingActionResolution.approved(pendingActionId));

            // 已批准的写文件步骤直接执行；下一个高危步骤（提交）再次挂起等待审批。
            assertThat(resumed.status()).isEqualTo(AgentState.Status.WAITING);
            assertThat(writes).hasValue(1);
            assertThat(commits).hasValue(0);

            String secondActionId = secondRuntime.invocation(invocationId)
                    .orElseThrow().pendingAction().pendingActionId();
            AgentState committed = secondRuntime.resume(
                    invocationId, PendingActionResolution.approved(secondActionId));

            assertThat(committed.status()).isEqualTo(AgentState.Status.COMPLETED);
            assertThat(committed.output()).isEqualTo("文件已写入并提交。");
            assertThat(commits).hasValue(1);
            assertThat(checkpointStore.load(invocationId)).isEmpty();
            assertThat(continuationStore.load(invocationId)).isEmpty();
        }
    }
}
