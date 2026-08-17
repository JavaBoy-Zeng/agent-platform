package com.github.agentos.agent;

import com.github.agentos.kernel.AgentContext;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.planner.AgentDecision;
import com.github.agentos.planner.AgentPlan;
import com.github.agentos.planner.AgentPlanner;
import com.github.agentos.planner.PlanExecutionSnapshot;
import com.github.agentos.planner.PlanOrigin;
import com.github.agentos.planner.PlanOutcome;
import com.github.agentos.planner.PlanStep;
import com.github.agentos.planner.PlanType;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolRegistry;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 最小 REACT 循环测试。 */
class ReactExecutionStrategyTest {

    @Test
    void feedsToolResultBackToModelAndReturnsFinalAnswer() {
        AtomicInteger toolCalls = new AtomicInteger();
        AgentPlanner planner = new AgentPlanner() {
            @Override
            public AgentPlan createPlan(AgentRequest request, AgentContext context) {
                return toolPlan();
            }

            @Override
            public AgentPlan replan(
                    AgentRequest request, AgentContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                assertThat(snapshot.lastResult().output()).isEqualTo("sunny");
                return finalPlan("It is sunny.");
            }

            @Override
            public AgentDecision decide(
                    AgentRequest request, AgentContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) {
                return AgentDecision.from(replan(request, context, previousPlan, snapshot));
            }
        };
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "weather"; }
            @Override public String description() { return "weather lookup"; }
            @Override public ToolResult execute(ToolCall call) {
                toolCalls.incrementAndGet();
                return ToolResult.success("sunny");
            }
        };
        ReactExecutionStrategy strategy = new ReactExecutionStrategy(
                planner, new ToolDispatcher(new ToolRegistry(List.of(tool))),
                new DefaultAgentFinalizer(), new AgentExecutionLimits(0, 4, 4, 2));

        AgentState result = strategy.run(
                AgentRequest.of("session-1", "weather"), AgentContext.of("main-agent"),
                AgentState.ready().startNextIteration());

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("It is sunny.");
        assertThat(toolCalls).hasValue(1);
    }

    @Test
    void stopsWhenModelCallLimitIsReached() {
        AgentPlanner planner = new AgentPlanner() {
            @Override public AgentPlan createPlan(
                    AgentRequest request, AgentContext context) { return toolPlan(); }
            @Override public AgentPlan replan(
                    AgentRequest request, AgentContext context, AgentPlan previousPlan,
                    PlanExecutionSnapshot snapshot) { return toolPlan(); }
        };
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "weather"; }
            @Override public String description() { return "weather lookup"; }
            @Override public ToolResult execute(ToolCall call) { return ToolResult.success("sunny"); }
        };
        ReactExecutionStrategy strategy = new ReactExecutionStrategy(
                planner, new ToolDispatcher(new ToolRegistry(List.of(tool))),
                new DefaultAgentFinalizer(), new AgentExecutionLimits(0, 4, 4, 1));

        AgentState result = strategy.run(
                AgentRequest.of("session-1", "weather"), AgentContext.of("main-agent"),
                AgentState.ready().startNextIteration());

        assertThat(result.status()).isEqualTo(AgentState.Status.FAILED);
        assertThat(result.error()).contains("maxModelCalls");
    }

    private static AgentPlan toolPlan() {
        return AgentPlan.create(
                PlanType.EXECUTION, PlanOrigin.INITIAL, PlanOutcome.CONTINUE, "lookup",
                List.of(new PlanStep("step-1", "weather", false,
                        new ToolCall("weather", Map.of()))), "");
    }

    private static AgentPlan finalPlan(String answer) {
        return AgentPlan.create(
                PlanType.EXECUTION, PlanOrigin.REPLANNED, PlanOutcome.COMPLETE,
                "answer", List.of(), answer);
    }
}
