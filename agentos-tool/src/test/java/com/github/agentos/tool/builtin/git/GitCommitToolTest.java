package com.github.agentos.tool.builtin.git;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolContexts;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolResult;
import com.github.agentos.tool.builtin.git.GitCommitTool;
import com.github.agentos.tool.builtin.file.access.RootedFileAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitCommitToolTest {

    @TempDir
    Path repository;

    private GitCommitTool tool;

    @BeforeEach
    void initializeRepository() throws Exception {
        git("init");
        git("config", "user.name", "AgentOS Test");
        git("config", "user.email", "agentos@example.test");
        Files.writeString(repository.resolve("base.txt"), "base");
        git("add", "base.txt");
        git("commit", "-m", "initial");
        tool = new GitCommitTool(new RootedFileAccessPolicy(repository));
    }

    @Test
    void commitsOnlyExplicitPathsWithoutPushing() throws Exception {
        Files.writeString(repository.resolve("requested.md"), "requested");
        Files.writeString(repository.resolve("unrelated.txt"), "unrelated");

        ToolResult result = tool.execute(ToolContexts.testContext(tool), call(
                List.of("requested.md"), "docs: add requested file"));

        assertThat(result.success()).isTrue();
        Map<?, ?> data = (Map<?, ?>) result.data();
        assertThat(data.get("message")).isEqualTo("docs: add requested file");
        assertThat(data.get("paths")).isEqualTo(List.of("requested.md"));
        assertThat(data.get("commitId")).asString().hasSize(40);
        assertThat(git("show", "--pretty=", "--name-only", "HEAD"))
                .isEqualTo("requested.md");
        assertThat(git("status", "--porcelain"))
                .contains("?? unrelated.txt").doesNotContain("requested.md");
        assertThat(result.metadata()).containsEntry("pushed", false);
    }

    @Test
    void refusesRepositoryWithExistingStagedChanges() throws Exception {
        Files.writeString(repository.resolve("already-staged.txt"), "staged");
        Files.writeString(repository.resolve("requested.md"), "requested");
        git("add", "already-staged.txt");

        ToolResult result = tool.execute(ToolContexts.testContext(tool), call(
                List.of("requested.md"), "docs: add requested file"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.SECURITY_DENIED);
        assertThat(result.error()).contains("already contains staged changes");
        assertThat(git("diff", "--cached", "--name-only"))
                .isEqualTo("already-staged.txt");
        assertThat(git("status", "--porcelain")).contains("?? requested.md");
    }

    @Test
    void rejectsUnsafeOrUnchangedPathsAndDeclaresHighRisk() {
        ToolResult escaping = tool.execute(ToolContexts.testContext(tool), call(
                List.of("../outside.txt"), "docs: unsafe"));
        ToolResult repositoryRoot = tool.execute(ToolContexts.testContext(tool), call(
                List.of("."), "docs: everything"));
        ToolResult directoryPath = tool.execute(ToolContexts.testContext(tool), call(
                List.of(".git/.."), "docs: directory"));
        ToolResult unchanged = tool.execute(ToolContexts.testContext(tool), call(
                List.of("base.txt"), "docs: unchanged"));

        assertThat(escaping.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
        assertThat(repositoryRoot.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
        assertThat(directoryPath.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
        assertThat(unchanged.failureType()).isEqualTo(ToolFailureType.INVALID_ARGUMENT);
        assertThat(tool.riskLevel()).isEqualTo(AgentTool.RiskLevel.HIGH);
        assertThat(tool.parameters()).extracting(parameter -> parameter.name())
                .containsExactly("repository", "paths", "message");
    }

    @Test
    void rejectsRepositoryOutsideAllowedRoot(@TempDir Path otherRoot) throws Exception {
        GitCommitTool restricted = new GitCommitTool(new RootedFileAccessPolicy(otherRoot));

        ToolResult result = restricted.execute(
                ToolContexts.testContext(restricted), call(
                        List.of("base.txt"), "docs: forbidden repository"));

        assertThat(result.success()).isFalse();
        assertThat(result.failureType()).isEqualTo(ToolFailureType.SECURITY_DENIED);
        assertThat(result.error()).contains("outside the allowed root");
    }

    private ToolCall call(List<String> paths, String message) {
        return new ToolCall("git_commit", Map.of(
                "repository", repository.toString(),
                "paths", paths,
                "message", message));
    }

    private String git(String... arguments) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(
                List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .trim();
        assertThat(process.waitFor()).as(String.join(" ", command) + "\n" + output).isZero();
        return output;
    }
}
