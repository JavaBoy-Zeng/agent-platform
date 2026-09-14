package com.github.agentos.kernel;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;

/** 完整执行记录与模型上下文分离；大内容存为产物，事件仅携带预览和下载标识。 */
public final class ExecutionTrace {
    private static final ThreadLocal<InvocationContext> CURRENT = new ThreadLocal<>();
    private ExecutionTrace() { }

    public static Scope open(InvocationContext context) { return new Scope(context); }

    public static void recordCurrent(String callId, String kind, String title, String content) {
        InvocationContext context = CURRENT.get();
        if (context != null) record(context, callId, kind, title, content);
    }

    public static void record(InvocationContext context, String callId, String kind, String title, String content) {
        if (context.invocation() == null) return;
        // 取消后的最后一段响应仍需归档；完成短暂的本地记录后恢复中断状态。
        boolean interrupted = Thread.interrupted();
        try { recordActive(context, callId, kind, title, content); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static void recordActive(InvocationContext context, String callId, String kind, String title, String content) {
        String text = content == null ? "" : content;
        String sessionId = context.invocation().modelUsageSessionId();
        String rootId = context.invocationId().split("/sub/", 2)[0];
        String traceId = UUID.randomUUID().toString();
        var data = new LinkedHashMap<String, Object>();
        data.put("traceId", traceId);
        data.put("traceKind", kind);
        data.put("callId", callId);
        data.put("agentId", context.agentId());
        data.put("executionInvocationId", context.invocationId());
        data.put("rootInvocationId", rootId);
        data.put("contentChars", text.length());
        data.put("modelCallsUsed", context.invocation().totalModelCalls());
        data.put("modelCallsRemaining", context.invocation().remainingModelCalls());
        boolean archived = false;
        try {
            var artifact = context.artifacts().save(sessionId, rootId,
                    "trace-" + kind + "-" + traceId + ".txt", "text/plain; charset=UTF-8",
                    text.getBytes(StandardCharsets.UTF_8));
            if (artifact.isPresent()) {
                data.put("artifactId", artifact.get().artifactId());
                archived = true;
            }
        } catch (RuntimeException exception) {
            data.put("archiveError", "完整记录归档失败，已保留在事件中");
        }
        data.put("archived", archived);
        // 无产物存储时不能静默丢弃正文：保留事件原文并明确未归档。
        data.put("text", archived && text.length() > 4_000
                ? text.substring(0, 2_000) + "\n…预览省略，展开加载完整记录…\n" + text.substring(text.length() - 2_000)
                : text);
        try {
            context.eventPublisher().publish(new DefaultAgentEvent(traceId, sessionId, rootId,
                    context.agentId(), java.time.Instant.now(), AgentEventType.STEP_COMPLETED, title, data));
        } catch (RuntimeException ignored) { /* 观察端错误不得改变执行结果。 */ }
    }

    public static final class Scope implements AutoCloseable {
        private final InvocationContext previous;
        private Scope(InvocationContext context) { previous = CURRENT.get(); CURRENT.set(context); }
        @Override public void close() {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
