package com.github.agentos.server.catalog;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.routing.AgentCapability;
import com.github.agentos.agent.routing.RoutableAgent;
import com.github.agentos.agent.routing.RouteAcceptance;
import com.github.agentos.agent.routing.RouteScope;
import com.github.agentos.agent.routing.SupervisorRouteDecision;
import com.github.agentos.agent.workflow.BaseAgent;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.server.catalog.RuntimeCatalogService.CatalogSnapshot;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 不调用模型和网络、只读取当前进程目录快照的系统自省 Agent。 */
public final class SystemCatalogAgent extends BaseAgent implements Agent, RoutableAgent {
    public static final String ID = "system-catalog-agent";

    private final Supplier<CatalogSnapshot> snapshotSupplier;

    public SystemCatalogAgent(Supplier<CatalogSnapshot> snapshotSupplier) {
        super(ID, "读取当前 AgentOS 实例已注册的工具、Agent、Skill、MCP、模型和运行限制",
                List.of());
        this.snapshotSupplier = java.util.Objects.requireNonNull(snapshotSupplier);
    }

    @Override
    public Set<AgentCapability> capabilities() {
        return Set.of(AgentCapability.RUNTIME_CATALOG_READ);
    }

    @Override
    public RouteAcceptance accepts(AgentRequest request, SupervisorRouteDecision decision) {
        if (decision.scope() != RouteScope.LOCAL_RUNTIME) {
            return RouteAcceptance.reject("system catalog only accepts LOCAL_RUNTIME requests");
        }
        if (!decision.requiredCapabilities().contains(AgentCapability.RUNTIME_CATALOG_READ)) {
            return RouteAcceptance.reject("system catalog requires RUNTIME_CATALOG_READ intent");
        }
        return RoutableAgent.super.accepts(request, decision);
    }

    @Override
    public AgentExecutionResult run(AgentRequest request, InvocationContext context) {
        return AgentExecutionResult.from(run(
                request, context, AgentState.ready().startNextIteration(), AgentEventSink.NOOP), null);
    }

    @Override
    public AgentState run(
            AgentRequest request,
            InvocationContext context,
            AgentState runningState,
            AgentEventSink eventSink) {
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_STARTED, request.sessionId(), request.objective(),
                Map.of("agentId", ID, "scope", RouteScope.LOCAL_RUNTIME.name())));
        String answer = render(request.objective(), snapshotSupplier.get());
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.OUTPUT_DELTA, request.sessionId(), answer,
                Map.of("agentId", ID, "sequence", 0, "source", "runtime-catalog")));
        eventSink.emit(AgentRunEvent.of(
                AgentRunEvent.Type.RUN_COMPLETED, request.sessionId(), answer,
                Map.of("agentId", ID, "scope", RouteScope.LOCAL_RUNTIME.name())));
        return runningState.complete(answer);
    }

    static String render(String objective, CatalogSnapshot snapshot) {
        String text = objective.toLowerCase(Locale.ROOT);
        String requestedObject = text.replace("agentos", "");
        if (containsAny(requestedObject, "工具", "tool")) return renderTools(snapshot);
        if (containsAny(requestedObject, "agent", "智能体")) return renderAgents(snapshot);
        if (containsAny(requestedObject, "skill", "技能")) return renderSkills(snapshot);
        if (requestedObject.contains("mcp")) return renderMcp(snapshot);
        if (containsAny(requestedObject, "模型", "model")) return renderModels(snapshot);
        if (containsAny(requestedObject, "限制", "预算", "limit")) return renderLimits(snapshot);
        return "当前 AgentOS 运行时已注册：%d 个 Agent、%d 个工具、%d 个 Skill、%d 个 MCP Server、%d 个已启用模型。"
                .formatted(snapshot.agents().size(), snapshot.tools().size(), snapshot.skills().size(),
                        snapshot.mcpServers().size(), snapshot.models().size());
    }

    private static String renderTools(CatalogSnapshot snapshot) {
        StringBuilder answer = new StringBuilder("当前 AgentOS 共注册 ")
                .append(snapshot.tools().size()).append(" 个工具：\n");
        snapshot.tools().forEach(tool -> answer.append("\n- `").append(tool.name())
                .append("`：").append(tool.description()));
        return answer.toString();
    }

    private static String renderAgents(CatalogSnapshot snapshot) {
        StringBuilder answer = new StringBuilder("当前 AgentOS 共注册 ")
                .append(snapshot.agents().size()).append(" 个 Agent：\n");
        snapshot.agents().forEach(agent -> answer.append("\n- `").append(agent.id())
                .append("`：").append(agent.description()));
        return answer.toString();
    }

    private static String renderSkills(CatalogSnapshot snapshot) {
        StringBuilder answer = new StringBuilder("当前 AgentOS 共加载 ")
                .append(snapshot.skills().size()).append(" 个 Skill：\n");
        snapshot.skills().forEach(skill -> answer.append("\n- `").append(skill.id())
                .append("`：").append(skill.description()));
        return answer.toString();
    }

    private static String renderMcp(CatalogSnapshot snapshot) {
        StringBuilder answer = new StringBuilder("当前 AgentOS 共配置 ")
                .append(snapshot.mcpServers().size()).append(" 个 MCP Server：\n");
        snapshot.mcpServers().forEach(server -> answer.append("\n- `").append(server.name())
                .append("`：").append(server.status()));
        return answer.toString();
    }

    private static String renderModels(CatalogSnapshot snapshot) {
        StringBuilder answer = new StringBuilder("当前 AgentOS 已启用模型：\n");
        snapshot.models().forEach(model -> answer.append("\n- `").append(model.modelId())
                .append("`：").append(model.provider()).append("（").append(model.modelType())
                .append("）"));
        return answer.toString();
    }

    private static String renderLimits(CatalogSnapshot snapshot) {
        StringBuilder answer = new StringBuilder("当前 AgentOS 单次运行限制：\n");
        snapshot.limits().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> answer.append("\n- `").append(entry.getKey())
                        .append("`：").append(entry.getValue()));
        return answer.toString();
    }

    private static boolean containsAny(String text, String... signals) {
        for (String signal : signals) if (text.contains(signal)) return true;
        return false;
    }
}
