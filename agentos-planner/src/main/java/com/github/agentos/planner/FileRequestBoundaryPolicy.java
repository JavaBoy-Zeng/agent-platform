package com.github.agentos.planner;

import com.github.agentos.kernel.AgentRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在模型规划和工具调度前拒绝用户请求中明确越界的文件系统目标。 */
final class FileRequestBoundaryPolicy {

    private static final Pattern POSIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![\\p{L}\\p{N}_:/])(/[^\\s，。！？；：,!?;\\\"'）)】\\]}]*)");
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])([a-z]:[\\\\/][^\\s，。！？；：,!?;\\\"'）)】\\]}]*)");
    private static final Set<String> FILE_OPERATION_SIGNALS = Set.of(
            "列出", "读取", "查看", "搜索", "查找", "写入", "创建", "修改", "删除",
            "访问", "遍历", "打开", "检查", "统计", "复制", "移动", "重命名",
            "list", "read", "write", "search", "find", "inspect", "open", "copy", "move");

    private FileRequestBoundaryPolicy() {
    }

    /**
     * 对用户明确给出的绝对路径做无副作用的词法边界检查。
     *
     * <p>这里只处理可以确定的父目录/兄弟目录越界。位于根目录内的路径仍由文件工具
     * 执行真实路径、符号链接和文件权限的最终授权。</p>
     */
    static Optional<AgentPlan> denyExplicitOutsidePath(
            AgentRequest request, Path allowedRoot, PlanOrigin origin) {
        if (allowedRoot == null || !requestsFileOperation(request.objective())) {
            return Optional.empty();
        }
        Path normalizedRoot = allowedRoot.toAbsolutePath().normalize();
        return absolutePaths(request.objective()).stream()
                .map(FileRequestBoundaryPolicy::pathOrNull)
                .filter(java.util.Objects::nonNull)
                .map(path -> path.toAbsolutePath().normalize())
                .filter(path -> !path.startsWith(normalizedRoot))
                .findFirst()
                .map(outside -> AgentPlan.create(
                        PlanType.EXECUTION,
                        origin,
                        PlanOutcome.COMPLETE,
                        request.objective(),
                        List.of(),
                        "目标路径 " + outside + " 位于允许根目录 " + normalizedRoot
                                + " 之外，无法访问。当前文件工具只能访问该根目录及其子目录；"
                                + "此次未调用文件工具。"));
    }

    private static boolean requestsFileOperation(String objective) {
        String normalized = objective == null ? "" : objective.toLowerCase(Locale.ROOT);
        return FILE_OPERATION_SIGNALS.stream().anyMatch(normalized::contains);
    }

    private static List<String> absolutePaths(String objective) {
        java.util.ArrayList<String> paths = new java.util.ArrayList<>();
        collect(POSIX_ABSOLUTE_PATH, objective, paths);
        collect(WINDOWS_ABSOLUTE_PATH, objective, paths);
        return List.copyOf(paths);
    }

    private static void collect(Pattern pattern, String value, List<String> paths) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
    }

    private static Path pathOrNull(String value) {
        try {
            return Path.of(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
