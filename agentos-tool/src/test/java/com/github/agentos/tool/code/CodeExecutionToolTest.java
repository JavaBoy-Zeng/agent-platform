package com.github.agentos.tool.code;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodeExecutionToolTest {

    /** 固定行为的假执行器：按语言返回可预判结果。 */
    private static final class FakeExecutor implements CodeExecutor {
        CodeExecutionRequest captured;
        boolean sandboxed = true;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public boolean supports(CodeLanguage language) {
            return language != CodeLanguage.JAVA;
        }

        @Override
        public boolean isSandboxed() {
            return sandboxed;
        }

        @Override
        public CodeExecutionResult execute(CodeExecutionRequest request) {
            captured = request;
            return new CodeExecutionResult(
                    0, "fake-output", "", 5, name(), false);
        }
    }

    @Test
    void delegatesToExecutorAndFormatsOutput() {
        FakeExecutor executor = new FakeExecutor();
        CodeExecutionTool tool = new CodeExecutionTool(executor);

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "execute_code",
                Map.of("language", "python", "code", "print('hi')", "timeout_seconds", 42)));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("fake-output");
        assertThat(executor.captured.language()).isEqualTo(CodeLanguage.PYTHON);
        assertThat(executor.captured.code()).isEqualTo("print('hi')");
        assertThat(executor.captured.timeoutSeconds()).isEqualTo(42);
    }

    @Test
    void riskLevelFollowsExecutorSandboxness() {
        FakeExecutor sandboxed = new FakeExecutor();
        FakeExecutor local = new FakeExecutor();
        local.sandboxed = false;

        assertThat(new CodeExecutionTool(sandboxed).riskLevel())
                .isEqualTo(AgentTool.RiskLevel.LOW);
        assertThat(new CodeExecutionTool(local).riskLevel())
                .isEqualTo(AgentTool.RiskLevel.HIGH);
    }

    @Test
    void descriptionKeepsHostAndNetworkCommandsOutOfSandbox() {
        String description = new CodeExecutionTool(new FakeExecutor()).description();

        assertThat(description)
                .contains("独立、自包含")
                .contains("curl", "run_command")
                .contains("沙箱无网络");
    }

    @Test
    void languageAliasesAreNormalized() {
        FakeExecutor executor = new FakeExecutor();
        CodeExecutionTool tool = new CodeExecutionTool(executor);

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "execute_code", Map.of("language", "Python", "code", "x")));

        assertThat(result.success()).isTrue();
        assertThat(executor.captured.language()).isEqualTo(CodeLanguage.PYTHON);
    }

    @Test
    void unsupportedLanguageFails() {
        FakeExecutor executor = new FakeExecutor();
        CodeExecutionTool tool = new CodeExecutionTool(executor);

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "execute_code", Map.of("language", "java", "code", "class Main{}")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not supported");
    }

    @Test
    void invalidLanguageFailsWithGuidance() {
        CodeExecutionTool tool = new CodeExecutionTool(new FakeExecutor());

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "execute_code", Map.of("language", "cobol", "code", "x")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("unsupported language")
                .contains("python/shell/java/javascript/go");
    }

    @Test
    void missingCodeFails() {
        CodeExecutionTool tool = new CodeExecutionTool(new FakeExecutor());

        ToolResult result = tool.execute(ToolContexts.testContext(tool), new ToolCall(
                "execute_code", Map.of("language", "python")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("code");
    }

    @Test
    void languageParsingAcceptsAliasesAndRejectsUnknown() {
        assertThat(CodeLanguage.parse("PYTHON")).isEqualTo(CodeLanguage.PYTHON);
        assertThat(CodeLanguage.parse(" shell ")).isEqualTo(CodeLanguage.SHELL);
        assertThat(CodeLanguage.parse("node")).isEqualTo(CodeLanguage.JAVASCRIPT);
        assertThat(CodeLanguage.parse("golang")).isEqualTo(CodeLanguage.GO);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> CodeLanguage.parse("ruby"));
    }
}
