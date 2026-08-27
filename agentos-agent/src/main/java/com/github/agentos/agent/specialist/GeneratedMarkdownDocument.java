package com.github.agentos.agent.specialist;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * ReportAgent 生成结果的最终成品。
 *
 * <p>模型即使没有完全遵守提示词，也只允许把最终 Markdown 正文交给文件工具：
 * 推理标签、外层代码围栏、生成过程说明和重复草稿都会在这里被清理。</p>
 *
 * @param title    文档一级标题
 * @param content  可直接保存的 Markdown 正文
 * @param fileName 由文档标题派生的安全文件名
 */
record GeneratedMarkdownDocument(String title, String content, String fileName) {

    private static final Pattern REASONING_BLOCK = Pattern.compile(
            "(?is)<(?:[a-z0-9_.-]+:)?think\\b[^>]*>.*?"
                    + "</(?:[a-z0-9_.-]+:)?think\\s*>");
    private static final Pattern REASONING_OPEN_TAG = Pattern.compile(
            "(?i)<(?:[a-z0-9_.-]+:)?think(?:\\s[^>]*)?>");
    private static final Pattern REASONING_CLOSE_TAG = Pattern.compile(
            "(?i)</(?:[a-z0-9_.-]+:)?think\\s*>");
    private static final Pattern WRAPPED_MARKDOWN = Pattern.compile(
            "(?is)^[ \\t]*```(?:markdown|md)[ \\t]*\\R(.*)\\R[ \\t]*```[ \\t]*$");
    private static final Pattern MARKDOWN_OPEN_LINE = Pattern.compile(
            "(?im)^[ \\t]*```(?:markdown|md)[ \\t]*$");
    private static final Pattern MARKDOWN_FENCE = Pattern.compile(
            "(?ims)^[ \\t]*```(?:markdown|md)[ \\t]*\\R(.*?)^[ \\t]*```[ \\t]*$");
    private static final Pattern FENCE_MARKER = Pattern.compile(
            "(?m)^[ \\t]*(`{3,}|~{3,})[^\\r\\n]*$");
    private static final Pattern H1 = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern GENERIC_TITLE = Pattern.compile(
            "^(?:顶部)?引用块$|^(?:markdown\\s*)?(?:文档|正文|报告|总结报告)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final int MAX_TITLE_CODE_POINTS = 48;
    private static final int MAX_FILE_NAME_CODE_POINTS = 60;

    GeneratedMarkdownDocument {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(fileName, "fileName must not be null");
    }

    /** 从模型原始响应中提取唯一、可下载的 Markdown 成品。 */
    static GeneratedMarkdownDocument from(String objective, String rawResponse) {
        Objects.requireNonNull(objective, "objective must not be null");
        Objects.requireNonNull(rawResponse, "rawResponse must not be null");

        String candidate = stripReasoning(rawResponse.replace("\r\n", "\n").replace('\r', '\n'));
        candidate = lastMarkdownFence(candidate).orElse(candidate).strip();
        candidate = keepLastDocument(candidate);

        String objectiveTitle = deriveTitle(objective);
        List<MatchResult> headings = headingsOutsideCodeFences(candidate);
        String title;
        if (!headings.isEmpty()) {
            MatchResult heading = headings.get(0);
            String modelTitle = cleanHeading(heading.group(1));
            title = isGenericTitle(modelTitle) ? objectiveTitle : modelTitle;
            candidate = title.equals(heading.group(1))
                    ? candidate.substring(heading.start()).strip()
                    : candidate.substring(heading.start(), heading.start(1))
                            + title + candidate.substring(heading.end(1));
        } else {
            title = objectiveTitle;
            candidate = "# " + title + "\n\n" + candidate;
        }

        String content = candidate.strip() + "\n";
        return new GeneratedMarkdownDocument(title, content, deriveFileName(title));
    }

    /** 优先选择模型最后返回的 markdown 围栏；它通常是思考结束后的最终版本。 */
    private static java.util.Optional<String> lastMarkdownFence(String value) {
        String stripped = value.strip();
        Matcher openLines = MARKDOWN_OPEN_LINE.matcher(stripped);
        int openLineCount = 0;
        while (openLines.find()) {
            openLineCount++;
        }
        if (openLineCount == 1) {
            Matcher wrapped = WRAPPED_MARKDOWN.matcher(stripped);
            if (wrapped.matches()) {
                return java.util.Optional.of(wrapped.group(1));
            }
        }
        Matcher matcher = MARKDOWN_FENCE.matcher(value);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return java.util.Optional.ofNullable(last);
    }

    /** 文档约定只能有一个 H1；出现多个时保留最后一份，避免重复草稿进入产物。 */
    private static String keepLastDocument(String value) {
        List<MatchResult> headings = headingsOutsideCodeFences(value);
        if (headings.isEmpty()) {
            return value.strip();
        }
        return value.substring(headings.get(headings.size() - 1).start()).strip();
    }

    /** 只识别正文中的 H1，忽略 bash 等代码块内形如“# comment”的内容。 */
    private static List<MatchResult> headingsOutsideCodeFences(String value) {
        List<MatchResult> headings = new ArrayList<>();
        Matcher matcher = H1.matcher(value);
        while (matcher.find()) {
            if (!insideCodeFence(value, matcher.start())) {
                headings.add(matcher.toMatchResult());
            }
        }
        return headings;
    }

    private static boolean insideCodeFence(String value, int position) {
        Matcher markers = FENCE_MARKER.matcher(value);
        char activeMarker = 0;
        int activeLength = 0;
        while (markers.find() && markers.start() < position) {
            String marker = markers.group(1);
            if (activeMarker == 0) {
                activeMarker = marker.charAt(0);
                activeLength = marker.length();
            } else if (marker.charAt(0) == activeMarker && marker.length() >= activeLength) {
                activeMarker = 0;
                activeLength = 0;
            }
        }
        return activeMarker != 0;
    }

    /** 清除标准 think 与带厂商命名空间的推理标签。 */
    private static String stripReasoning(String value) {
        String visible = REASONING_BLOCK.matcher(value).replaceAll("");
        Matcher danglingClose = REASONING_CLOSE_TAG.matcher(visible);
        int lastCloseEnd = -1;
        while (danglingClose.find()) {
            lastCloseEnd = danglingClose.end();
        }
        if (lastCloseEnd >= 0) {
            visible = visible.substring(lastCloseEnd);
        }
        Matcher danglingOpen = REASONING_OPEN_TAG.matcher(visible);
        if (danglingOpen.find()) {
            visible = visible.substring(0, danglingOpen.start());
        }
        return REASONING_CLOSE_TAG.matcher(visible).replaceAll("").strip();
    }

    /** 从用户目标中去掉“请生成并保存一份关于”等命令性前缀。 */
    private static String deriveTitle(String objective) {
        String title = objective.replace('_', ' ')
                .replaceAll("(?i)\\.md$", "")
                .replaceAll("\\s+", " ")
                .strip();
        title = title.replaceFirst("^(?:请你?|麻烦你?|劳驾|帮我|给我)\\s*", "");
        title = title.replaceFirst(
                "^(?:生成并保存|生成和保存|生成|创建|撰写|编写|整理|制作|写)\\s*", "");
        title = title.replaceFirst("^(?:一份|一个|一篇)\\s*", "");
        title = title.replaceFirst("^关于\\s*", "");
        title = title.replaceFirst("(?:并)?(?:保存|下载)(?:为|成)?(?:一份|一个)?(?:文档|文件)?.*$", "");
        title = title.replaceFirst("[。；;]+$", "").strip();
        title = title.replaceFirst("(?:文档|报告|总结|文件)$", "").strip();
        if (title.isBlank()) {
            title = "报告";
        }
        return truncate(title, MAX_TITLE_CODE_POINTS);
    }

    private static String cleanHeading(String heading) {
        String cleaned = heading.replaceAll("[*_`]+", "")
                .replaceAll("\\s+", " ")
                .strip();
        return truncate(cleaned.isBlank() ? "报告" : cleaned, MAX_TITLE_CODE_POINTS);
    }

    private static boolean isGenericTitle(String title) {
        return GENERIC_TITLE.matcher(title.strip()).matches();
    }

    /** 文件名仅保留跨平台稳定的字母、数字和连字符，不再追加难读的时间戳。 */
    private static String deriveFileName(String title) {
        String safe = title.replaceAll("[^\\p{L}\\p{N}.-]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[. -]+|[. -]+$", "");
        if (safe.isBlank()) {
            safe = "report";
        }
        return truncate(safe, MAX_FILE_NAME_CODE_POINTS) + ".md";
    }

    private static String truncate(String value, int maximumCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maximumCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints)).strip();
    }
}
