package com.github.agentos.server;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FileWriteToolConfigurationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolDispatcher toolDispatcher;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void exposesSingleDispatcherWithoutLegacyExecutorBean() {
        assertThat(toolDispatcher).isNotNull();
        assertThat(applicationContext.getBeansOfType(ToolDispatcher.class)).hasSize(1);
        assertThat(applicationContext.containsBean("toolExecutor")).isFalse();
    }

    @Test
    void registersFileWriteToolAsHighRisk() {
        AgentTool tool = toolRegistry.require("file_write");

        assertThat(tool.riskLevel()).isEqualTo(AgentTool.RiskLevel.HIGH);
        assertThat(tool.parameters()).extracting(parameter -> parameter.name())
                .containsExactly("path", "content", "mode", "createParentDirectories");
    }

    @Test
    void registersGitCommitToolAsHighRisk() {
        AgentTool tool = toolRegistry.require("git_commit");

        assertThat(tool.riskLevel()).isEqualTo(AgentTool.RiskLevel.HIGH);
        assertThat(tool.parameters()).extracting(parameter -> parameter.name())
                .containsExactly("repository", "paths", "message");
    }
}
