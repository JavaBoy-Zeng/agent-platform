package com.github.agentos.server.catalog;

import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.AgentState;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.server.catalog.RuntimeCatalogService.CatalogSnapshot;
import com.github.agentos.server.catalog.RuntimeCatalogService.ToolView;
import com.github.agentos.server.catalog.RuntimeCatalogService.SkillView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemCatalogAgentTest {

    @Test
    void rendersExactRuntimeToolSnapshotWithoutModelOrNetwork() {
        CatalogSnapshot snapshot = new CatalogSnapshot(
                List.of(),
                List.of(
                        new ToolView("alpha", "first tool", "LOW", 0, List.of()),
                        new ToolView("web_search", "search public web", "LOW", 1,
                                List.of("query"))),
                List.of(), List.of(), List.of(), Map.of());
        SystemCatalogAgent agent = new SystemCatalogAgent(() -> snapshot);

        AgentState result = agent.run(
                AgentRequest.of("s1", "AgentOS 现在有哪些工具"),
                InvocationContext.of(SystemCatalogAgent.ID),
                AgentState.ready().startNextIteration(), event -> { });

        assertThat(result.output())
                .contains("共注册 2 个工具", "`alpha`：first tool",
                        "`web_search`：search public web");
    }

    @Test
    void agentOsNameDoesNotMakeSkillQuestionRenderAgentList() {
        CatalogSnapshot snapshot = new CatalogSnapshot(
                List.of(), List.of(),
                List.of(new SkillView("review", "Review", "review code", "classpath")),
                List.of(), List.of(), Map.of());

        String answer = SystemCatalogAgent.render("AgentOS 有哪些 Skill", snapshot);

        assertThat(answer).contains("共加载 1 个 Skill", "`review`：review code");
    }
}
