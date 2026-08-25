package com.github.agentos.server.config;

import com.github.agentos.tool.code.CodeExecutionTool;
import com.github.agentos.tool.code.CodeExecutor;
import com.github.agentos.tool.code.LocalProcessCodeExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 代码执行器装配测试。 */
class CodeExecutorConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(CodeExecutorConfiguration.class));

    @Test
    void defaultLocalModeRejectsHostProcessExecution() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "code executor local is disabled by "
                                    + "agentos.security.allow-host-processes=false; use docker mode");
        });
    }

    @Test
    void localMode_createsLocalExecutorAndTool() {
        runner.withPropertyValues(
                        "agentos.tools.code-executor.mode=local",
                        "agentos.security.allow-host-processes=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(CodeExecutor.class);
                    assertThat(context.getBean(CodeExecutor.class))
                            .isInstanceOf(LocalProcessCodeExecutor.class);
                    assertThat(context).hasSingleBean(CodeExecutionTool.class);
                    assertThat(context.getBean(CodeExecutionTool.class).name())
                            .isEqualTo("execute_code");
                    // 本地进程无沙箱，工具按 HIGH 风险走 HITL 审批。
                    assertThat(context.getBean(CodeExecutionTool.class).riskLevel())
                            .isEqualTo(com.github.agentos.tool.api.AgentTool.RiskLevel.HIGH);
                });
    }

    @Test
    void dockerMode_createsSandboxedTool() {
        runner.withPropertyValues("agentos.tools.code-executor.mode=docker")
                .run(context -> {
                    assertThat(context).hasSingleBean(CodeExecutor.class);
                    assertThat(context.getBean(CodeExecutor.class).isSandboxed()).isTrue();
                    assertThat(context.getBean(CodeExecutionTool.class).riskLevel())
                            .isEqualTo(com.github.agentos.tool.api.AgentTool.RiskLevel.LOW);
                });
    }

    @Test
    void invalidMode_failsStartup() {
        runner.withPropertyValues("agentos.tools.code-executor.mode=bogus")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void disabled_skipsAllBeans() {
        runner.withPropertyValues("agentos.tools.code-executor.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CodeExecutor.class);
                    assertThat(context).doesNotHaveBean(CodeExecutionTool.class);
                });
    }
}
