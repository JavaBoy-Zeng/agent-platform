package com.github.agentos.server.config;

import com.github.agentos.agent.loop.MainAgent;
import com.github.agentos.agent.specialist.CodeAgent;
import com.github.agentos.agent.specialist.SupervisorAgent;
import com.github.agentos.planner.ChatClient;
import com.github.agentos.tool.builtin.file.FileWriteTool;
import com.github.agentos.tool.builtin.shell.RunCommandTool;
import com.github.agentos.tool.builtin.web.WebFetchTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 专业 Agent 条件装配测试。 */
class SpecialistConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(SpecialistConfiguration.class))
            .withBean(ChatClient.class, () -> mock(ChatClient.class))
            .withBean(FileWriteTool.class, () -> mock(FileWriteTool.class))
            .withBean(WebFetchTool.class, () -> mock(WebFetchTool.class))
            .withBean(MainAgent.class, () -> mock(MainAgent.class));

    @Test
    void disabledRunCommandSkipsCodeAgentWithoutBreakingStartup() {
        runner.withPropertyValues("agentos.tools.run-command.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CodeAgent.class);
                    assertThat(context).doesNotHaveBean("codeAgentTool");
                    assertThat(context).hasSingleBean(SupervisorAgent.class);
                });
    }

    @Test
    void enabledRunCommandCreatesCodeAgent() {
        runner.withPropertyValues(
                        "agentos.tools.run-command.enabled=true",
                        "agentos.security.allow-host-processes=true")
                .withBean(RunCommandTool.class, () -> mock(RunCommandTool.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CodeAgent.class);
                    assertThat(context).hasBean("codeAgentTool");
                    assertThat(context).hasSingleBean(SupervisorAgent.class);
                });
    }
}
