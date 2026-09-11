package com.github.agentos.planner;

import com.github.agentos.planner.flow.HistoryProcessor;
import com.github.agentos.tool.api.ToolFailureType;
import com.github.agentos.tool.runtime.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件事实的确定性运行时约束。
 *
 * <p>模型提示词只能降低幻觉概率，不能保证模型一定遵守。该策略在
 * 模型输出转成可执行计划前强制两个不变量：文件续读游标必须消费；
 * 文件结论必须有当前 Invocation 的工具证据。</p>
 */
final class FileGroundingPolicy {

    private static final Pattern METADATA_BLOCK = Pattern.compile(
            "\\[file_read_metadata]\\R(.*?)\\R\\[/file_read_metadata]",
            Pattern.DOTALL);
    private static final Pattern POSIX_FILE_PATH = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])(/[^\\s]+?\\.(?:md|markdown|txt|pdf|docx?|json|ya?ml|xml|csv|tsv))"
                    + "(?=$|[\\s，。！？；：,!?;:)\\]】])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOWS_FILE_PATH = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_])([a-z]:[\\\\/][^\\s]+?\\.(?:md|markdown|txt|pdf|docx?|json|ya?ml|xml|csv|tsv))"
                    + "(?=$|[\\s，。！？；：,!?;:)\\]】])");
    private static final Pattern LISTED_ORGANIZATION = Pattern.compile(
            "(?m)^\\s*(?:[-*#]+\\s*|\\d+[.、]\\s*)"
                    + "(?:\\*\\*)?[\"“]?([^\\n（(]{2,60}?"
                    + "(?:有限责任公司|股份有限公司|有限公司|银行|大学|学院|二手车))"
                    + "(?:\\*\\*)?[\"”]?\\s*(?=[（(]|[-—:]|$)");
    private static final Pattern BOLD_ORGANIZATION = Pattern.compile(
            "\\*\\*([^*\\n]{2,60}?(?:有限责任公司|股份有限公司|有限公司|银行|大学|学院|二手车))\\*\\*");
    private static final Set<String> FILE_OBJECTIVE_SIGNALS = Set.of(
            "文件", "简历", "文档", "附件", "文本");
    private static final Set<String> FILE_EVIDENCE_ACTIONS = Set.of(
            "读取", "阅读", "查看", "检查", "核对", "验证", "提取", "列出",
            "内容", "里面", "文中", "包含", "是否", "有吗", "有没有", "哪些", "哪几");
    private static final Pattern FILE_SCOPED_SEARCH = Pattern.compile(
            "(?:在|从)?(?:这个|该|上述|本地)?(?:文件|文档|附件|简历)(?:中|里|内)?(?:搜索|查找)"
                    + "|(?:搜索|查找)(?:这个|该|上述|本地)?(?:文件|文档|附件|简历)(?:中|里|内)?");
    private static final Set<String> NEGATIVE_SIGNALS = Set.of(
            "没有", "不在", "未包含", "不包含", "无匹配", "未找到", "不存在", "并非");
    private static final Set<String> FILE_ACCESS_DENIAL_SIGNALS = Set.of(
            "无法访问", "不能访问", "无权访问", "没有权限", "不允许访问", "拒绝访问",
            "路径越界", "outside the allowed root", "outside allowed root");
    private static final Set<String> FILE_BOUNDARY_SIGNALS = Set.of(
            "允许根目录", "访问根目录", "allowedroot", "allowed root");

    private FileGroundingPolicy() {
    }

    static ModelPlan enforce(
            ModelPlan modelPlan,
            PlanningRequest request,
            ToolRegistry toolRegistry) {
        List<ReadCursor> pending = pendingReads(request.executionSnapshot());
        if (!pending.isEmpty() && !consumesPendingCursor(modelPlan, pending)) {
            return continuationPlan(request, toolRegistry, pending.getFirst());
        }

        if (modelPlan.outcome() != PlanOutcome.COMPLETE) {
            return modelPlan;
        }
        boolean fileGrounded = requiresFreshFileEvidence(request);
        if (fileGrounded && !hasFreshFileEvidence(request.executionSnapshot())
                && !isFileAccessBoundaryAnswer(modelPlan)) {
            return initialReadPlan(request, toolRegistry).orElseThrow(() ->
                    new PlanValidationException(List.of(
                            "file-grounded completion requires a current file_read/file_search observation")));
        }

        List<String> unsupported = fileGrounded
                ? unsupportedOrganizations(modelPlan, request)
                : List.of();
        if (!unsupported.isEmpty()) {
            if (!hasCompletedFileRead(request.executionSnapshot())) {
                Optional<ModelPlan> readPlan = initialReadPlan(request, toolRegistry);
                if (readPlan.isPresent()) {
                    return readPlan.get();
                }
            }
            throw new PlanValidationException(List.of(
                    "final answer contains file-derived organizations without current tool evidence: "
                            + String.join(", ", unsupported)));
        }
        return modelPlan;
    }

    /**
     * 访问边界说明不是文件内容结论，因此不应强制要求先产生文件读取证据。
     *
     * <p>必须同时包含明确拒绝语义和根目录边界语义，避免普通的“没找到文件”回答绕过
     * 文件事实校验。</p>
     */
    private static boolean isFileAccessBoundaryAnswer(ModelPlan modelPlan) {
        String answer = modelPlan.finalAnswer();
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String normalized = answer.toLowerCase(Locale.ROOT);
        return FILE_ACCESS_DENIAL_SIGNALS.stream().anyMatch(normalized::contains)
                && FILE_BOUNDARY_SIGNALS.stream().anyMatch(normalized::contains);
    }

    private static boolean requiresFreshFileEvidence(PlanningRequest request) {
        if (Boolean.TRUE.equals(request.agentRequest().attributes().get(
                HistoryProcessor.REQUIRES_FILE_EVIDENCE_ATTRIBUTE))) {
            return true;
        }
        String objective = request.agentRequest().objective().toLowerCase(Locale.ROOT);
        return (FILE_OBJECTIVE_SIGNALS.stream().anyMatch(objective::contains)
                && (FILE_EVIDENCE_ACTIONS.stream().anyMatch(objective::contains)
                        || FILE_SCOPED_SEARCH.matcher(objective).find()))
                || findFilePath(objective).isPresent();
    }

    private static boolean hasFreshFileEvidence(PlanExecutionSnapshot snapshot) {
        return snapshot != null && snapshot.stepResults().stream()
                .anyMatch(FileGroundingPolicy::isCompletedFileEvidence);
    }

    private static boolean hasCompletedFileRead(PlanExecutionSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        return snapshot.stepResults().stream()
                .filter(result -> result.status() == StepStatus.COMPLETED)
                .filter(result -> (result.toolName().equals("file_read") || result.toolName().equals("workspace-agent")))
                .flatMap(result -> metadata(result.output()).stream())
                .anyMatch(values -> !Boolean.parseBoolean(values.getOrDefault("hasMore", "false")));
    }

    private static boolean isCompletedFileEvidence(StepResult result) {
        return result.status() == StepStatus.COMPLETED
                && ((result.toolName().equals("file_read") || result.toolName().equals("workspace-agent") && !metadata(result.output()).isEmpty()) || result.toolName().equals("file_search"));
    }

    private static Optional<ModelPlan> initialReadPlan(
            PlanningRequest request,
            ToolRegistry toolRegistry) {
        if (request.maxSteps() <= 0 || toolRegistry.find("file_read").isEmpty()) {
            return Optional.empty();
        }
        String source = request.agentRequest().objective() + "\n"
                + request.agentRequest().attributes().getOrDefault(
                        HistoryProcessor.CONVERSATION_HISTORY_ATTRIBUTE, "") + "\n"
                + request.memoryContext().formattedContext();
        return findFilePath(source).map(path -> new ModelPlan(
                PlanType.DISCOVERY,
                PlanOutcome.CONTINUE,
                request.agentRequest().objective(),
                List.of(new ModelPlan.Step(
                        "read-file-evidence",
                        "读取文件以获取当前请求所需的可验证证据",
                        false,
                        "file_read",
                        Map.of("path", path, "offset", 0))),
                null));
    }

    private static ModelPlan continuationPlan(
            PlanningRequest request,
            ToolRegistry toolRegistry,
            ReadCursor cursor) {
        if (request.maxSteps() <= 0 || toolRegistry.find("file_read").isEmpty()) {
            throw new PlanValidationException(List.of(
                    "file_read has an unconsumed continuation but no step budget remains"));
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("path", cursor.path());
        if (cursor.nextPage() > 0) {
            arguments.put("page", cursor.nextPage());
        }
        arguments.put("offset", cursor.nextOffset());
        return new ModelPlan(
                PlanType.DISCOVERY,
                PlanOutcome.CONTINUE,
                request.agentRequest().objective(),
                List.of(new ModelPlan.Step(
                        "continue-file-read-" + Math.max(cursor.nextPage(), 0)
                                + "-" + cursor.nextOffset(),
                        "从 file_read 返回的续读位置继续读取未消费内容",
                        false,
                        "file_read",
                        arguments)),
                null);
    }

    private static boolean consumesPendingCursor(ModelPlan plan, List<ReadCursor> pending) {
        if (plan.outcome() != PlanOutcome.CONTINUE || plan.steps() == null) {
            return false;
        }
        for (ModelPlan.Step step : plan.steps()) {
            if (step == null || !"file_read".equals(step.toolName()) || step.arguments() == null) {
                continue;
            }
            String path = String.valueOf(step.arguments().getOrDefault("path", ""));
            int page = integer(step.arguments().get("page"), 0);
            int offset = integer(step.arguments().get("offset"), 0);
            boolean matches = pending.stream().anyMatch(cursor ->
                    samePath(cursor.path(), path)
                            && (cursor.nextPage() == 0 || cursor.nextPage() == page)
                            && cursor.nextOffset() == offset);
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static List<ReadCursor> pendingReads(PlanExecutionSnapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        Map<String, ReadCursor> pending = new LinkedHashMap<>();
        for (StepResult result : snapshot.stepResults()) {
            if (!(result.toolName().equals("file_read") || result.toolName().equals("workspace-agent"))
                    || result.status() != StepStatus.COMPLETED
                    || result.failureType() != ToolFailureType.NONE) {
                continue;
            }
            for (Map<String, String> values : metadata(result.output())) {
                String path = values.getOrDefault("path", "").strip();
                if (path.isEmpty()) {
                    continue;
                }
                if (Boolean.parseBoolean(values.getOrDefault("hasMore", "false"))) {
                    pending.put(normalizedPath(path), new ReadCursor(
                            path,
                            integer(values.get("nextPage"), 0),
                            integer(values.get("nextOffset"), 0)));
                } else {
                    pending.remove(normalizedPath(path));
                }
            }
        }
        return List.copyOf(pending.values());
    }

    private static List<Map<String, String>> metadata(String output) {
        List<Map<String, String>> blocks = new ArrayList<>();
        Matcher matcher = METADATA_BLOCK.matcher(output == null ? "" : output);
        while (matcher.find()) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : matcher.group(1).split("\\R")) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    values.put(line.substring(0, separator).strip(),
                            line.substring(separator + 1).strip());
                }
            }
            blocks.add(values);
        }
        return blocks;
    }

    private static List<String> unsupportedOrganizations(
            ModelPlan modelPlan,
            PlanningRequest request) {
        if (modelPlan.finalAnswer() == null || request.executionSnapshot() == null) {
            return List.of();
        }
        String evidence = request.executionSnapshot().stepResults().stream()
                .filter(FileGroundingPolicy::isCompletedFileEvidence)
                .map(StepResult::output)
                .reduce("", (left, right) -> left + "\n" + right);
        Set<String> organizations = new LinkedHashSet<>();
        collectOrganizations(LISTED_ORGANIZATION, modelPlan.finalAnswer(), organizations);
        collectOrganizations(BOLD_ORGANIZATION, modelPlan.finalAnswer(), organizations);

        List<String> unsupported = new ArrayList<>();
        for (String organization : organizations) {
            if (evidence.contains(organization)) {
                continue;
            }
            if (request.agentRequest().objective().contains(organization)
                    && isNegated(modelPlan.finalAnswer(), organization)) {
                continue;
            }
            unsupported.add(organization);
        }
        return unsupported;
    }

    private static void collectOrganizations(
            Pattern pattern, String answer, Set<String> organizations) {
        Matcher matcher = pattern.matcher(answer);
        while (matcher.find()) {
            organizations.add(matcher.group(1).strip());
        }
    }

    private static boolean isNegated(String answer, String organization) {
        int index = answer.indexOf(organization);
        if (index < 0) {
            return false;
        }
        int from = Math.max(0, index - 120);
        int to = Math.min(answer.length(), index + organization.length() + 80);
        String context = answer.substring(from, to);
        return NEGATIVE_SIGNALS.stream().anyMatch(context::contains);
    }

    private static Optional<String> findFilePath(String value) {
        Matcher posix = POSIX_FILE_PATH.matcher(value == null ? "" : value);
        if (posix.find()) {
            return Optional.of(posix.group(1));
        }
        Matcher windows = WINDOWS_FILE_PATH.matcher(value == null ? "" : value);
        return windows.find() ? Optional.of(windows.group(1)) : Optional.empty();
    }

    private static boolean samePath(String first, String second) {
        return normalizedPath(first).equals(normalizedPath(second));
    }

    private static String normalizedPath(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize().toString();
        } catch (RuntimeException ignored) {
            return value;
        }
    }

    private static int integer(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private record ReadCursor(String path, int nextPage, int nextOffset) {
    }
}
