package com.github.agentos.server;

import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FileWriteToolConfigurationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void registersFileWriteToolAsHighRisk() {
        AgentTool tool = toolRegistry.require("file_write");

        assertThat(tool.riskLevel()).isEqualTo(AgentTool.RiskLevel.HIGH);
        assertThat(tool.parameters()).extracting(parameter -> parameter.name())
                .containsExactly("path", "content", "mode", "createParentDirectories");
    }
}
