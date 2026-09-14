package com.github.agentos.server.run;

import com.github.agentos.kernel.AgentRunEvent;
import com.github.agentos.kernel.AgentStreamEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 将 Runtime 内部事件转换成不泄露模型推理或厂商协议的用户事件描述。 */
final class RuntimeStreamEventMapper {
    private RuntimeStreamEventMapper() { }

    static List<MappedEvent> map(AgentRunEvent event) {
        if (event == null || event.data().containsKey("traceKind")) return List.of();
        return switch (event.type()) {
            case PLAN_CREATED -> List.of(status("正在分析并规划任务", event));
            case ROUTE_DECIDED -> List.of(status("正在选择执行 Agent", event));
            case REPLAN -> List.of(status("正在调整执行计划", event));
            case DECISION -> "HUMAN_ACTION_REQUIRED".equals(
                    text(event.data().get("domainEventType")))
                    && event.data().containsKey("pendingActionId")
                    && hasToolCallId(event)
                    ? List.of(awaitingApproval(event))
                    : List.of(status("正在整理下一步操作", event));
            case OBSERVATION -> List.of(status("正在检查工具结果", event));
            case USAGE -> List.of(usage(event));
            case TOOL_STARTED -> hasToolCallId(event) ? List.of(toolStarted(event)) : List.of();
            case TOOL_FINISHED -> hasToolCallId(event) ? toolFinished(event) : List.of();
            default -> List.of();
        };
    }

    private static boolean hasToolCallId(AgentRunEvent event) {
        return text(event.data().get("toolCallId")).length() > 0;
    }

    private static MappedEvent status(String message, AgentRunEvent source) {
        return new MappedEvent(AgentStreamEvent.Type.STATUS, "", agent(source), parent(source),
                Map.of("text", message));
    }

    private static MappedEvent usage(AgentRunEvent source) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("model", text(source.data().get("model")));
        data.put("inputTokens", number(source.data().get("inputTokens"),
                number(source.data().get("promptTokens"), 0)));
        data.put("outputTokens", number(source.data().get("outputTokens"),
                number(source.data().get("completionTokens"), 0)));
        data.put("cachedTokens", number(source.data().get("cachedTokens"), 0));
        data.put("totalTokens", number(source.data().get("totalTokens"),
                number(data.get("inputTokens"), 0) + number(data.get("outputTokens"), 0)));
        return new MappedEvent(AgentStreamEvent.Type.USAGE, "", agent(source), parent(source), data);
    }

    private static MappedEvent toolStarted(AgentRunEvent source) {
        String toolCallId = text(source.data().get("toolCallId"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", toolCallId);
        data.put("toolName", text(source.data().get("toolName")));
        data.put("arguments", safeMap(source.data().get("arguments")));
        return new MappedEvent(
                AgentStreamEvent.Type.TOOL_STARTED, toolCallId, agent(source), parent(source), data);
    }

    private static List<MappedEvent> toolFinished(AgentRunEvent source) {
        String toolCallId = text(source.data().get("toolCallId"));
        boolean success = Boolean.TRUE.equals(source.data().get("success"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolCallId", toolCallId);
        data.put("toolName", text(source.data().get("toolName")));
        data.put("arguments", safeMap(source.data().get("arguments")));
        data.put("summary", bounded(text(source.data().get("summary")), 500));
        data.put("truncated", Boolean.TRUE.equals(source.data().get("truncated")));
        String outputRef = text(source.data().get("outputRef"));
        if (!outputRef.isBlank()) data.put("outputRef", outputRef);

        if (!success) {
            String failureType = text(source.data().get("failureType"));
            data.put("error", Map.of(
                    "code", failureType.isBlank() ? "TOOL_FAILED" : "TOOL_" + failureType,
                    "message", bounded(source.message(), 1000),
                    "retryable", retryable(failureType)));
        }
        List<MappedEvent> mapped = new ArrayList<>();
        mapped.add(new MappedEvent(
                success ? AgentStreamEvent.Type.TOOL_COMPLETED : AgentStreamEvent.Type.TOOL_FAILED,
                toolCallId, agent(source), parent(source), data));

        String artifactId = text(source.data().get("artifactId"));
        if (success && !artifactId.isBlank()) {
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("artifactId", artifactId);
            artifact.put("filename", text(source.data().get("filename")));
            artifact.put("contentType", text(source.data().get("contentType")));
            artifact.put("sizeBytes", number(source.data().get("sizeBytes"), 0));
            mapped.add(new MappedEvent(
                    AgentStreamEvent.Type.ARTIFACT_CREATED, artifactId,
                    agent(source), parent(source), artifact));
        }
        return List.copyOf(mapped);
    }

    private static MappedEvent awaitingApproval(AgentRunEvent source) {
        String toolCallId = text(source.data().get("toolCallId"));
        String itemId = toolCallId.isBlank()
                ? text(source.data().get("pendingActionId")) : toolCallId;
        Map<String, Object> data = new LinkedHashMap<>(source.data());
        data.put("description", bounded(source.message(), 1000));
        return new MappedEvent(
                AgentStreamEvent.Type.TOOL_AWAITING_APPROVAL, itemId,
                agent(source), parent(source), data);
    }

    private static String agent(AgentRunEvent event) {
        return text(event.data().get("agentId"));
    }

    private static String parent(AgentRunEvent event) {
        return text(event.data().get("parentRunId"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Object value) {
        return value instanceof Map<?, ?> map
                ? Map.copyOf((Map<String, Object>) map) : Map.of();
    }

    private static long number(Object value, long fallback) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try { return Math.max(0, Long.parseLong(text(value))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean retryable(String failureType) {
        String normalized = failureType == null ? "" : failureType.toUpperCase(java.util.Locale.ROOT);
        return normalized.contains("TRANSIENT") || normalized.contains("TIMEOUT")
                || normalized.contains("RATE_LIMIT");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String bounded(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    record MappedEvent(
            AgentStreamEvent.Type type,
            String itemId,
            String agentId,
            String parentRunId,
            Map<String, Object> data) {
    }
}
