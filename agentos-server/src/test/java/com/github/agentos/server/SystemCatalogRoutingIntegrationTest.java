package com.github.agentos.server;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentRunner;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.server.catalog.SystemCatalogAgent;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证本地系统自省在完整 Spring 装配中不会进入模型或网络检索链路。 */
@SpringBootTest(properties = "agentos.memory.mode=memory")
class SystemCatalogRoutingIntegrationTest {

    @Autowired private AgentRunner runner;
    @Autowired private ToolRegistry toolRegistry;
    @MockitoBean private ChatClient chatClient;

    @Test
    void currentAgentOsToolsUsesRuntimeCatalogWithoutModelCall() {
        List<AgentRunEvent> events = new CopyOnWriteArrayList<>();

        AgentState result = runner.run(
                AgentRequest.of("catalog-routing-session", "AgentOS 现在有哪些工具"),
                InvocationContext.of("main-agent"), events::add);

        assertThat(result.status()).isEqualTo(AgentState.Status.COMPLETED);
        assertThat(result.output()).contains(
                "共注册 " + toolRegistry.definitions().size() + " 个工具");
        assertThat(events)
                .filteredOn(event -> event.type() == AgentRunEvent.Type.ROUTE_DECIDED)
                .anySatisfy(event -> assertThat(event.data())
                        .containsEntry("targetAgent", SystemCatalogAgent.ID));
        assertThat(events).noneMatch(event -> event.type() == AgentRunEvent.Type.TOOL_STARTED);
        verifyNoInteractions(chatClient);
    }
}
