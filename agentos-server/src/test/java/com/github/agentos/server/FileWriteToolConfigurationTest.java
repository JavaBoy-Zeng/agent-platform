package com.github.agentos.server;

import com.github.agentos.kernel.AgentExecutionLimits;
import com.github.agentos.kernel.AgentRequest;
import com.github.agentos.kernel.InvocationContext;
import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContext;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.file.access.FileAccessPolicy;
import com.github.agentos.tool.builtin.file.access.ProtectedConfigurationFileAccessPolicy;
import com.github.agentos.tool.runtime.ToolDispatcher;
import com.github.agentos.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FileWriteToolConfigurationTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.github.agentos.planner.ChatClient chatClient;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private ToolDispatcher toolDispatcher;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private FileAccessPolicy fileAccessPolicy;

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
    void registersDelegationsSeparatelyFromActualOperationRisk() {
        assertThat(toolRegistry.require("report-agent"))
                .isInstanceOf(com.github.agentos.tool.api.AgentDelegationTool.class);
        assertThat(toolRegistry.require("search-agent").riskLevel())
                .isEqualTo(AgentTool.RiskLevel.LOW);
    }

    @Test
    void requestApprovalStopsReportAgentBeforeItCanWriteAFile() {
        org.mockito.Mockito.when(chatClient.chat(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn("# 审批测试报告\n\n报告正文。");
        ToolResult result = toolDispatcher.dispatch(
                new ToolCall("report-agent", Map.of("objective", "生成并保存报告")),
                tool -> new ToolContext(
                        new AgentRequest("approval-session", "生成并保存报告",
                                Map.of("approvalMode", "REQUEST_APPROVAL")),
                        InvocationContext.of("plan-execute-agent"),
                        "approval-plan", "write-report",
                        AgentExecutionLimits.defaults(), Map.of(), tool));

        assertThat(result.actions().pendingAction()).isNotNull();
        assertThat(result.actions().pendingAction().payload())
                .containsEntry("toolName", "file_write")
                .containsEntry("delegationTool", "report-agent")
                .containsEntry("riskLevel", AgentTool.RiskLevel.HIGH.name());
        var arguments = (Map<?, ?>) result.actions().pendingAction().payload().get("arguments");
        assertThat(String.valueOf(arguments.get("content")).strip()).isEqualTo("# 审批测试报告\n\n报告正文。");
        toolDispatcher.discardPending(result.actions().pendingAction());
    }

    @Test
    void desktopToolsNeverEnableHostProcessesWithoutABinding() {
        for (String name : java.util.List.of("git_commit", "run_command")) {
            ToolResult result = toolDispatcher.dispatch(new ToolCall(name, Map.of()), tool -> new ToolContext(
                    new AgentRequest("unbound-" + name, "test", Map.of("approvalMode", "FULL_ACCESS")),
                    InvocationContext.of("workspace-agent"), "", "", AgentExecutionLimits.defaults(), Map.of(), tool));
            assertThat(result.success()).isFalse();
            assertThat(result.failureType()).isEqualTo(com.github.agentos.tool.api.ToolFailureType.PERMISSION_DENIED);
            assertThat(result.error()).contains("服务端命令执行仍处于禁用状态");
        }
    }

    @Test
    void protectsDeploymentConfigurationFilesAtTheServerBoundary() {
        assertThat(fileAccessPolicy)
                .isInstanceOf(ProtectedConfigurationFileAccessPolicy.class);
    }
}
