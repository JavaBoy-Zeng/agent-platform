package com.github.agentos.server;

import com.github.agentos.agent.Agent;
import com.github.agentos.agent.AgentExecutionResult;
import com.github.agentos.agent.registry.AgentRegistry;
import com.github.agentos.agent.registry.InMemoryAgentRegistry;
import com.github.agentos.agent.workflow.BaseAgent;
import com.github.agentos.kernel.AgentEventSink;
import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.server.controller.ConsoleCatalogController;
import com.github.agentos.server.catalog.RuntimeCatalogService;
import com.github.agentos.server.model.ModelClientProperties;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Console 运行时目录接口测试：验证 Agent 列表来自注册表而非硬编码。 */
class ConsoleCatalogControllerTest {

    @Test
    void catalogListsRegisteredAgentsAndMarksToolExposure() throws Exception {
        AgentRegistry registry = new InMemoryAgentRegistry(List.of(
                agent("main-agent", "planning entry"),
                agent("search-agent", "information retrieval")));
        ToolRegistry tools = new ToolRegistry(List.of(tool("search-agent"), tool("file_read")));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(registry, tools)).build();

        mvc.perform(get("/api/console/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.length()").value(2))
                .andExpect(jsonPath("$.agents[0].id").value("main-agent"))
                .andExpect(jsonPath("$.agents[0].name").value("Main Agent"))
                .andExpect(jsonPath("$.agents[0].kind").value("planner"))
                .andExpect(jsonPath("$.agents[0].exposedAsTool").value(false))
                .andExpect(jsonPath("$.agents[1].id").value("search-agent"))
                .andExpect(jsonPath("$.agents[1].kind").value("specialist"))
                .andExpect(jsonPath("$.agents[1].exposedAsTool").value(true))
                .andExpect(jsonPath("$.limits.maxSteps").value(30));
    }

    @Test
    void catalogFallsBackToMainAgentWhenRegistryIsEmpty() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                controller(new InMemoryAgentRegistry(), new ToolRegistry(List.of()))).build();

        mvc.perform(get("/api/console/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents.length()").value(1))
                .andExpect(jsonPath("$.agents[0].id").value("main-agent"));
    }

    @Test
    void agentDetailExposesSubAgents() throws Exception {
        AgentRegistry registry = new InMemoryAgentRegistry();
        registry.register(new WorkflowStub("pipeline-agent", List.of(
                new LeafStub("step-one"), new LeafStub("step-two"))));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                controller(registry, new ToolRegistry(List.of()))).build();

        mvc.perform(get("/api/console/agents/{agentId}", "pipeline-agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("workflow"))
                .andExpect(jsonPath("$.subAgents.length()").value(2))
                .andExpect(jsonPath("$.subAgents[0]").value("step-one"));
    }

    @Test
    void agentDetailUnknownReturns404() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                controller(new InMemoryAgentRegistry(), new ToolRegistry(List.of()))).build();

        mvc.perform(get("/api/console/agents/{agentId}", "missing"))
                .andExpect(status().isNotFound());
    }

    private static ConsoleCatalogController controller(
            AgentRegistry registry, ToolRegistry tools) {
        RuntimeCatalogService service = new RuntimeCatalogService(
                tools, provider(registry), provider(null), provider(null),
                new ModelClientProperties(), AgentExecutionLimits.defaults());
        return new ConsoleCatalogController(service);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() {
                return value;
            }

            @Override public T getObject(Object... args) {
                return value;
            }

            @Override public T getIfAvailable() {
                return value;
            }

            @Override public T getIfUnique() {
                return value;
            }
        };
    }

    private static Agent agent(String id, String description) {
        return new Agent() {
            @Override public String id() {
                return id;
            }

            @Override public String description() {
                return description;
            }

            @Override public AgentExecutionResult run(
                    AgentRequest request, InvocationContext context) {
                return AgentExecutionResult.from(
                        AgentState.ready().startNextIteration().complete("ok"), null);
            }
        };
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override public String name() {
                return name;
            }

            @Override public String description() {
                return "test tool";
            }

            @Override public List<ToolParameter> parameters() {
                return List.of();
            }

            @Override public ToolResult execute(ToolContext context, ToolCall call) {
                return ToolResult.success("ok");
            }
        };
    }

    /** 带子 Agent 的编排 Agent，用于验证 workflow 形态推断。 */
    private static final class WorkflowStub extends BaseAgent implements Agent {
        WorkflowStub(String id, List<BaseAgent> subAgents) {
            super(id, "workflow stub", subAgents);
        }

        @Override public AgentState run(
                AgentRequest request, InvocationContext context,
                AgentState runningState, AgentEventSink eventSink) {
            return runningState.complete("ok");
        }

        @Override public AgentExecutionResult run(
                AgentRequest request, InvocationContext context) {
            return AgentExecutionResult.from(
                    AgentState.ready().startNextIteration().complete("ok"), null);
        }
    }

    /** 叶子子 Agent。 */
    private static final class LeafStub extends BaseAgent {
        LeafStub(String id) {
            super(id, "leaf stub", List.of());
        }

        @Override public AgentState run(
                AgentRequest request, InvocationContext context,
                AgentState runningState, AgentEventSink eventSink) {
            return runningState.complete("ok");
        }
    }
}
