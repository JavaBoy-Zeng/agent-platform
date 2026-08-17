package com.github.agentos.tool.builtin.git;

import com.github.agentos.tool.api.AgentTool;
import com.github.agentos.tool.api.ToolCall;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.api.ToolParameter;
import com.github.agentos.tool.api.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 将明确列出的仓库相对路径提交到本地 Git 仓库，不执行推送。 */
public final class GitCommitTool implements AgentTool {

    private static final long COMMAND_TIMEOUT_SECONDS = 30;

    @Override
    public String name() {
        return "git_commit";
    }

    @Override
    public String description() {
        return "把明确列出的文件提交到本地 Git 仓库；不会推送，也不会包含其他改动。"
                + "仓库已有暂存内容时会拒绝执行。该操作会创建 Git commit，需要人工审批";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter(
                        "repository", ToolParameter.ValueType.STRING,
                        "Git 仓库根目录的完整路径", true),
                new ToolParameter(
                        "paths", ToolParameter.ValueType.ARRAY,
                        "要提交的仓库相对路径数组；只会暂存和提交这些路径", true),
                new ToolParameter(
                        "message", ToolParameter.ValueType.STRING,
                        "非空 Git commit message，建议使用 Conventional Commits 格式", true));
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.HIGH;
    }

    @Override
    public synchronized ToolResult execute(ToolCall call) {
        List<String> paths = List.of();
        Path repository = null;
        boolean stagedByTool = false;
        try {
            repository = repository(call.arguments().get("repository"));
            paths = paths(call.arguments().get("paths"), repository);
            String message = requiredString(
                    call.arguments().get("message"), "message").trim();
            if (message.length() > 500) {
                throw new IllegalArgumentException("message must not exceed 500 characters");
            }

            CommandResult staged = git(repository, "diff", "--cached", "--quiet", "--exit-code");
            if (staged.exitCode() == 1) {
                return ToolResult.failure(
                        ToolFailureType.SECURITY_DENIED,
                        "git commit refused: repository already contains staged changes");
            }
            requireSuccess(staged, "inspect staged changes");

            for (String path : paths) {
                CommandResult status = git(repository, "status", "--porcelain", "--", path);
                requireSuccess(status, "inspect path " + path);
                if (status.output().isBlank()) {
                    throw new IllegalArgumentException("path has no changes to commit: " + path);
                }
            }

            List<String> addArguments = new ArrayList<>(List.of("add", "--"));
            addArguments.addAll(paths);
            requireSuccess(git(repository, addArguments), "stage requested paths");
            stagedByTool = true;

            List<String> commitArguments = new ArrayList<>(
                    List.of("commit", "--only", "-m", message, "--"));
            commitArguments.addAll(paths);
            requireSuccess(git(repository, commitArguments), "create commit");
            stagedByTool = false;
            String commitId = git(repository, "rev-parse", "HEAD").requiredOutput("read commit id");
            return ToolResult.success(
                    Map.of(
                            "repository", repository.toString(),
                            "commitId", commitId,
                            "message", message,
                            "paths", paths),
                    "git commit created",
                    Map.of("pushed", false),
                    com.github.agentos.tool.api.ToolActions.none());
        } catch (IllegalArgumentException exception) {
            if (stagedByTool) unstage(repository, paths);
            return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT, exception.getMessage());
        } catch (Exception exception) {
            if (stagedByTool) unstage(repository, paths);
            return failure(exception);
        }
    }

    private static Path repository(Object value) throws IOException {
        Path requested = Path.of(requiredString(value, "repository"))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(requested)) {
            throw new IllegalArgumentException("repository is not a directory: " + requested);
        }
        Path real = requested.toRealPath();
        CommandResult rootResult = git(real, "rev-parse", "--show-toplevel");
        requireSuccess(rootResult, "resolve repository root");
        Path root = Path.of(rootResult.output()).toRealPath();
        if (!root.equals(real)) {
            throw new IllegalArgumentException(
                    "repository must be the Git worktree root: " + root);
        }
        return root;
    }

    private static List<String> paths(Object value, Path repository) {
        if (!(value instanceof Collection<?> collection) || collection.isEmpty()) {
            throw new IllegalArgumentException("paths must be a non-empty array");
        }
        if (collection.size() > 100) {
            throw new IllegalArgumentException("paths must not contain more than 100 entries");
        }
        List<String> result = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("every paths entry must be a non-blank string");
            }
            Path relative = Path.of(text.trim()).normalize();
            if (relative.isAbsolute() || relative.getNameCount() == 0
                    || relative.toString().isBlank() || relative.toString().equals(".")
                    || relative.startsWith("..") || relative.startsWith(".git")) {
                throw new IllegalArgumentException(
                        "path must be a safe repository-relative path: " + text);
            }
            Path resolved = repository.resolve(relative).normalize();
            if (!resolved.startsWith(repository)) {
                throw new IllegalArgumentException("path escapes repository: " + text);
            }
            if (Files.isDirectory(resolved)) {
                throw new IllegalArgumentException(
                        "path must identify a file, not a directory: " + text);
            }
            String normalized = relative.toString();
            if (!result.contains(normalized)) result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static CommandResult git(Path repository, String... arguments) throws IOException {
        return git(repository, List.of(arguments));
    }

    private static CommandResult git(Path repository, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        try {
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git command timed out: " + arguments.getFirst());
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .trim();
            return new CommandResult(process.exitValue(), output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("git command interrupted", exception);
        }
    }

    private static void requireSuccess(CommandResult result, String operation) {
        if (result.exitCode() != 0) {
            throw new IllegalArgumentException(
                    operation + " failed" + (result.output().isBlank() ? "" : ": " + result.output()));
        }
    }

    private static void unstage(Path repository, List<String> paths) {
        if (repository == null || paths.isEmpty()) return;
        try {
            List<String> arguments = new ArrayList<>(List.of("restore", "--staged", "--"));
            arguments.addAll(paths);
            git(repository, arguments);
        } catch (Exception ignored) {
            // 保留工作区内容；失败详情已经由原始提交错误返回。
        }
    }

    private static String requiredString(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing or blank required argument: " + name);
        }
        return text;
    }

    private static ToolResult failure(Exception exception) {
        ToolFailureType type = exception instanceof SecurityException
                ? ToolFailureType.SECURITY_DENIED
                : exception instanceof IOException
                        ? ToolFailureType.ACCESS_DENIED
                        : ToolFailureType.TOOL_INTERNAL_ERROR;
        String detail = exception.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = exception.getClass().getSimpleName();
        }
        return ToolResult.failure(type, "git commit failed: " + detail);
    }

    private record CommandResult(int exitCode, String output) {
        private String requiredOutput(String operation) {
            requireSuccess(this, operation);
            if (output.isBlank()) throw new IllegalArgumentException(operation + " returned no output");
            return output;
        }
    }
}
