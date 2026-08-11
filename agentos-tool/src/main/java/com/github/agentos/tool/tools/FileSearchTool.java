package com.github.agentos.tool.tools;

import com.github.agentos.tool.AgentTool;
import com.github.agentos.tool.ToolCall;
import com.github.agentos.tool.ToolFailureType;
import com.github.agentos.tool.ToolParameter;
import com.github.agentos.tool.ToolResult;
import com.github.agentos.tool.file.FileAccessPolicy;
import com.github.agentos.tool.FileToolSupport;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/** 按文件名 glob 或文本内容搜索本地目录的有界工具。 */
public final class FileSearchTool implements AgentTool {

    private static final int DEFAULT_MAX_DEPTH = 8;
    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int MAX_SCANNED_FILES = 20_000;
    private static final long MAX_CONTENT_FILE_BYTES = 1024L * 1024L;
    private final FileAccessPolicy accessPolicy;

    public FileSearchTool(FileAccessPolicy accessPolicy) {
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy must not be null");
    }

    @Override
    public String name() {
        return "file_search";
    }

    @Override
    public String description() {
        return "在目录中按 NAME（glob 文件名）或 CONTENT（文本字面量）搜索，不跟随符号链接";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                new ToolParameter("path", ToolParameter.ValueType.STRING, "搜索根目录", true),
                new ToolParameter("mode", ToolParameter.ValueType.STRING, "搜索模式：NAME 或 CONTENT", true),
                new ToolParameter("query", ToolParameter.ValueType.STRING, "NAME 模式的 glob 或 CONTENT 模式的字面量", true),
                new ToolParameter("maxDepth", ToolParameter.ValueType.INTEGER, "递归深度，默认 8，范围 0-12", false),
                new ToolParameter("maxResults", ToolParameter.ValueType.INTEGER, "最多结果数，默认 100，范围 1-500", false),
                new ToolParameter("caseSensitive", ToolParameter.ValueType.BOOLEAN, "是否区分大小写，默认 false", false));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String requested = FileToolSupport.requiredString(call.arguments().get("path"), "path");
            String mode = FileToolSupport.requiredString(call.arguments().get("mode"), "mode")
                    .toUpperCase(Locale.ROOT);
            String query = FileToolSupport.requiredString(call.arguments().get("query"), "query");
            int maxDepth = FileToolSupport.integer(
                    call.arguments().get("maxDepth"), "maxDepth", DEFAULT_MAX_DEPTH, 0, 12);
            int maxResults = FileToolSupport.integer(
                    call.arguments().get("maxResults"), "maxResults", DEFAULT_MAX_RESULTS, 1, 500);
            boolean caseSensitive = FileToolSupport.bool(
                    call.arguments().get("caseSensitive"), "caseSensitive", false);
            if (!mode.equals("NAME") && !mode.equals("CONTENT")) {
                return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT, "mode must be NAME or CONTENT");
            }

            Path root = accessPolicy.authorizeRead(Path.of(requested));
            if (!Files.exists(root)) {
                return ToolResult.failure(ToolFailureType.NOT_FOUND, "search path not found: " + root);
            }
            if (!Files.isDirectory(root)) {
                return ToolResult.failure(ToolFailureType.INVALID_ARGUMENT, "search path is not a directory: " + root);
            }

            SearchResult result = mode.equals("NAME")
                    ? searchNames(root, query, caseSensitive, maxDepth, maxResults)
                    : searchContents(root, query, caseSensitive, maxDepth, maxResults);
            List<String> output = new ArrayList<>(result.matches());
            if (output.isEmpty()) {
                output.add("[no matches]");
            }
            if (result.scanLimitReached()) {
                output.add("[scan truncated after " + MAX_SCANNED_FILES + " files]");
            } else if (result.resultLimitReached()) {
                output.add("[results truncated after " + maxResults + " matches]");
            }
            return ToolResult.success(String.join(System.lineSeparator(), output));
        } catch (Exception exception) {
            return FileToolSupport.failure(exception, "file search");
        }
    }

    private SearchResult searchNames(
            Path root, String query, boolean caseSensitive, int maxDepth, int maxResults) throws Exception {
        String effectiveQuery = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + effectiveQuery);
        List<String> matches = new ArrayList<>();
        int scanned = 0;
        boolean resultLimitReached = false;
        try (Stream<Path> paths = Files.walk(root, maxDepth + 1)) {
            Iterable<Path> files = () -> paths
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isRegularFile)
                    .iterator();
            for (Path path : files) {
                if (++scanned > MAX_SCANNED_FILES) {
                    return new SearchResult(matches, true, resultLimitReached);
                }
                Path authorized = accessPolicy.authorizeRead(path);
                Path relative = root.relativize(authorized);
                Path candidate = caseSensitive
                        ? relative
                        : Path.of(relative.toString().toLowerCase(Locale.ROOT));
                Path fileName = candidate.getFileName();
                if (matcher.matches(candidate) || (fileName != null && matcher.matches(fileName))) {
                    if (matches.size() >= maxResults) {
                        resultLimitReached = true;
                        break;
                    }
                    matches.add(relative.toString());
                }
            }
        }
        return new SearchResult(matches, false, resultLimitReached);
    }

    private SearchResult searchContents(
            Path root, String query, boolean caseSensitive, int maxDepth, int maxResults) throws Exception {
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        int scanned = 0;
        boolean resultLimitReached = false;
        try (Stream<Path> paths = Files.walk(root, maxDepth + 1)) {
            Iterable<Path> files = () -> paths
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(Files::isRegularFile)
                    .iterator();
            for (Path path : files) {
                if (++scanned > MAX_SCANNED_FILES) {
                    return new SearchResult(matches, true, resultLimitReached);
                }
                Path authorized = accessPolicy.authorizeRead(path);
                if (!Files.isReadable(authorized)
                        || Files.size(authorized) > MAX_CONTENT_FILE_BYTES
                        || isProbablyBinary(authorized)) {
                    continue;
                }
                try (BufferedReader reader = Files.newBufferedReader(authorized, StandardCharsets.UTF_8)) {
                    String line;
                    int lineNumber = 0;
                    while ((line = reader.readLine()) != null) {
                        lineNumber++;
                        String haystack = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                        if (haystack.contains(needle)) {
                            if (matches.size() >= maxResults) {
                                resultLimitReached = true;
                                break;
                            }
                            matches.add(root.relativize(authorized) + ":" + lineNumber + ": " + abbreviate(line));
                        }
                    }
                } catch (java.nio.charset.MalformedInputException ignored) {
                    // 非 UTF-8/二进制文件不影响其余搜索结果。
                }
                if (resultLimitReached) {
                    break;
                }
            }
        }
        return new SearchResult(matches, false, resultLimitReached);
    }

    private static boolean isProbablyBinary(Path path) throws Exception {
        byte[] prefix;
        try (var input = Files.newInputStream(path)) {
            prefix = input.readNBytes(8_192);
        }
        for (byte value : prefix) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static String abbreviate(String line) {
        String trimmed = line.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "...";
    }

    private record SearchResult(
            List<String> matches, boolean scanLimitReached, boolean resultLimitReached) {
    }
}
