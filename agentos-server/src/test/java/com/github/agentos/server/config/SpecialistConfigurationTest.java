package com.github.agentos.server.config;

import com.github.agentos.agent.loop.PlanExecuteAgent;
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
            .withBean(com.github.agentos.kernel.CheckpointStore.class,
                    com.github.agentos.kernel.InMemoryCheckpointStore::new)
            .withBean(ChatClient.class, () -> mock(ChatClient.class))
            .withBean(FileWriteTool.class, () -> mock(FileWriteTool.class))
            .withBean(WebFetchTool.class, () -> mock(WebFetchTool.class))
            .withBean(com.github.agentos.agent.loop.ReactAgent.class,
                    () -> mock(com.github.agentos.agent.loop.ReactAgent.class))
            .withBean(PlanExecuteAgent.class, () -> mock(PlanExecuteAgent.class));

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"plan", "react", ""})
    void bothAgentsRemainRoutableForEitherDefault(String mode) {
        runner.withPropertyValues(mode.isEmpty() ? new String[0]
                : new String[]{"agentos.agent.loop.mode=" + mode}).run(context -> {
            assertThat(context).hasNotFailed();
            ChatClient chat = context.getBean(ChatClient.class);
            PlanExecuteAgent main = context.getBean(PlanExecuteAgent.class);
            var react = context.getBean(com.github.agentos.agent.loop.ReactAgent.class);
            var request = com.github.agentos.kernel.AgentRequest.of("s1", "task");
            var invocation = com.github.agentos.kernel.InvocationContext.of("supervisor-agent");
            var running = com.github.agentos.kernel.AgentState.ready().startNextIteration();
            org.mockito.Mockito.when(main.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(running.complete("plan"));
            org.mockito.Mockito.when(react.run(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                    .thenReturn(running.complete("react"));
            for (String target : java.util.List.of("plan-execute-agent", "react-agent")) {
                org.mockito.Mockito.when(chat.chat(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any())).thenReturn("""
                        {"intent":"test","scope":"GENERAL","requiredCapabilities":["GENERAL_PLANNING"],
                         "targetAgent":"%s","confidence":0.95,"reason":"test","clarifyingQuestion":""}
                        """.formatted(target));
                assertThat(context.getBean(SupervisorAgent.class).run(request, invocation, running).output())
                        .isEqualTo(target.equals("plan-execute-agent") ? "plan" : "react");
            }
            org.mockito.Mockito.when(chat.chat(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any())).thenReturn("invalid");
            assertThat(context.getBean(SupervisorAgent.class).run(request, invocation, running).output())
                    .isEqualTo(mode.isEmpty() ? "react" : mode);
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"plan", "react"})
    void reactConfigurationCreatesAgentForBothDefaults(String mode) {
        new ApplicationContextRunner()
                .withConfiguration(UserConfigurations.of(ReactLoopConfiguration.class))
                .withPropertyValues("agentos.agent.loop.mode=" + mode)
                .withBean(ChatClient.class, () -> mock(ChatClient.class))
                .withBean(com.github.agentos.tool.runtime.ToolRegistry.class,
                        () -> new com.github.agentos.tool.runtime.ToolRegistry(java.util.List.of()))
                .withBean(com.github.agentos.tool.runtime.ToolDispatcher.class,
                        () -> mock(com.github.agentos.tool.runtime.ToolDispatcher.class))
                .withBean(com.github.agentos.kernel.AgentExecutionLimits.class,
                        com.github.agentos.kernel.AgentExecutionLimits::defaults)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(com.github.agentos.agent.loop.ReactAgent.class);
                });
    }

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
