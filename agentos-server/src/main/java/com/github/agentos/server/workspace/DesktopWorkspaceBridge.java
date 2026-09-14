package com.github.agentos.server.workspace;

import com.github.agentos.kernel.*;
import com.github.agentos.server.registry.AgentRunTaskRegistry;
import com.github.agentos.tool.api.*;
import com.github.agentos.tool.runtime.*;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated, session-scoped desktop mailbox. Local paths are never opened by the server. */
@Component
public final class DesktopWorkspaceBridge implements ToolInterceptor, Ordered {
    public static final String STATE_KEY = "desktopWorkspace";
    private static final Set<String> LOCAL_TOOLS = Set.of(
            "directory_list", "file_read", "file_search", "file_write", "run_command", "git_commit");
    private final SessionService sessions;
    private final AgentRunTaskRegistry tasks;
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();
    public DesktopWorkspaceBridge(SessionService sessions, AgentRunTaskRegistry tasks) {
        this.sessions = sessions;
        this.tasks = tasks;
    }
    // Risk approval must run before a desktop operation is enqueued.
    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

    public record Binding(String workspaceId, String root, String branch, String device, String osName) {
        public Binding {
            for (String value : Arrays.asList(workspaceId, root, device, osName)) {
                if (value == null || value.isBlank() || value.length() > 4096) throw new IllegalArgumentException("invalid workspace binding");
            }
            branch = branch == null ? "" : branch;
        }
        public Map<String, Object> metadata(String sessionId) {
            return Map.of("workspaceId", workspaceId, "root", root, "branch", branch,
                    "device", device, "osName", osName, "workspaceSessionId", sessionId);
        }
    }
    public record Registration(String connectionId, String token, Map<String, Object> runtime) {}
    public record Operation(String id, String toolName, Map<String, Object> arguments, long deadline) {}
    public record Poll(Operation operation, List<String> pendingIds, boolean running) {}
    public record Completion(boolean success, Object data, String error, boolean unavailable) {}
    private static final class Pending {
        final Operation operation;
        final CompletableFuture<Completion> result = new CompletableFuture<>();
        Pending(Operation operation) { this.operation = operation; }
    }
    private static final class Connection {
        final String id = UUID.randomUUID().toString();
        final String token = UUID.randomUUID().toString();
        final String sessionId;
        final String userId;
        final Binding binding;
        final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>(64);
        final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();
        volatile long heartbeat = System.currentTimeMillis();
        Connection(String sessionId, String userId, Binding binding) {
            this.sessionId = sessionId; this.userId = userId; this.binding = binding;
        }
        boolean online() { return System.currentTimeMillis() - heartbeat < 15_000; }
    }
    public synchronized Registration register(String sessionId, String userId, Binding binding) {
        Session session = sessions.getOrCreate(sessionId, userId);
        Object saved = session.state().value(STATE_KEY);
        if (saved instanceof Map<?, ?> previous && (!binding.workspaceId().equals(previous.get("workspaceId"))
                || !binding.root().equals(previous.get("root")))) {
            throw conflict("当前任务已绑定其他工作区，不能修改");
        }
        Connection old = connections.get(sessionId);
        if (old != null && (tasks.isRunning(sessionId) || !old.pending.isEmpty())) {
            throw conflict("任务正在执行，请保持现有本机连接");
        }
        Connection next = new Connection(sessionId, userId, binding);
        connections.put(sessionId, next);
        sessions.applyDelta(sessionId, Map.of(STATE_KEY, binding.metadata(sessionId)));
        return new Registration(next.id, next.token, binding.metadata(sessionId));
    }
    private static org.springframework.web.server.ResponseStatusException conflict(String message) {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, message);
    }
    private Connection authorize(String sessionId, String userId, String token) {
        Connection c = connections.get(sessionId);
        if (c == null || !c.userId.equals(userId) || !c.token.equals(token)
                || sessions.findByUser(sessionId, userId).isEmpty()) {
            throw new IllegalArgumentException("desktop connection not found");
        }
        return c;
    }
    public Poll poll(String sessionId, String userId, String token) {
        Connection c = authorize(sessionId, userId, token);
        c.heartbeat = System.currentTimeMillis();
        Pending next;
        do { next = c.queue.poll(); }
        while (next != null && !c.pending.containsKey(next.operation.id()));
        return new Poll(next == null ? null : next.operation, List.copyOf(c.pending.keySet()), tasks.isRunning(sessionId));
    }
    public void complete(String sessionId, String userId, String token, String id, Completion result) {
        Connection c = authorize(sessionId, userId, token);
        Pending p = c.pending.get(id);
        if (p != null) p.result.complete(result); // retries acknowledge the same result, never re-execute
    }
    public void disconnect(String sessionId, String userId, String token) {
        Connection c = authorize(sessionId, userId, token);
        c.heartbeat = 0;
        c.pending.values().forEach(p -> p.result.complete(new Completion(false, null, "本机连接已关闭", true)));
    }
    public AgentRequest prepare(AgentRequest request, String userId) {
        Map<String, Object> saved = saved(request.sessionId(), userId);
        if (saved == null) {
            if (request.attributes().containsKey("workspaceId")) throw conflict("本机工作区尚未连接，请重新连接后再运行");
            return request;
        }
        Connection c = connections.get(request.sessionId());
        if (c == null || !c.online()) throw conflict("本机工作区离线，请重新连接后再运行");
        if (request.attributes().containsKey("workspaceId")
                && !c.binding.workspaceId().equals(request.attributes().get("workspaceId"))) {
            throw conflict("任务工作区与本机连接不一致");
        }
        Map<String, Object> attributes = new LinkedHashMap<>(request.attributes());
        attributes.put("workspaceId", c.binding.workspaceId());
        attributes.put("workspaceSessionId", request.sessionId());
        attributes.put("workspaceRuntime", saved);
        return new AgentRequest(request.sessionId(), request.objective(), attributes);
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> saved(String sessionId, String userId) {
        Object value = sessions.findByUser(sessionId, userId).map(s -> s.state().value(STATE_KEY)).orElse(null);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
    @Override public ToolBeforeResult beforeExecute(ToolCall call, ToolContext context) {
        if (!LOCAL_TOOLS.contains(call.toolName())) return ToolBeforeResult.allow();
        String sessionId = String.valueOf(context.request().attributes().getOrDefault(
                "workspaceSessionId", context.request().sessionId()));
        Map<String, Object> saved = saved(sessionId, context.invocation().userId());
        if (saved == null && !context.request().attributes().containsKey("workspaceId")) return ToolBeforeResult.allow();
        Connection c = connections.get(sessionId);
        if (saved == null || c == null || !c.userId.equals(context.invocation().userId()) || !c.online()) {
            return pause("本机工作区离线。请打开桌面端并重新连接工作区后继续；不会回退到服务端目录。", call);
        }
        Object expected = context.request().attributes().get("workspaceRuntime");
        if (expected instanceof Map<?, ?> runtime && (!Objects.equals(runtime.get("branch"), c.binding.branch())
                || !Objects.equals(runtime.get("workspaceId"), c.binding.workspaceId()))) {
            return pause("本机工作区或分支已改变，请恢复任务原来的目录和分支后继续。", call);
        }
        Operation operation = new Operation(UUID.randomUUID().toString(), call.toolName(), call.arguments(),
                System.currentTimeMillis() + 135_000);
        Pending pending = new Pending(operation);
        c.pending.put(operation.id(), pending);
        try {
            if (!c.queue.offer(pending)) return pause("本机操作队列已满，请稍后继续。", call);
            while (true) {
                if (context.invocation().cancellation().isCancelled()) return ToolBeforeResult.shortCircuit(
                        ToolResult.failure(ToolFailureType.CANCELLED, "本机操作已取消"));
                if (!c.online() || System.currentTimeMillis() > operation.deadline()) {
                    return pause("本机连接中断或操作超时。操作可能已经执行，请先核对文件和命令结果，再决定是否继续。", call);
                }
                try {
                    Completion result = pending.result.get(1, TimeUnit.SECONDS);
                    if (result.unavailable()) return pause(result.error(), call);
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("workspace", c.binding.metadata(sessionId));
                    data.put("result", result.data() == null ? "" : result.data());
                    ToolResult output = result.success() ? ToolResult.success(formatResult(call.toolName(), data, result.data()))
                            : new ToolResult(ToolStatus.FAILURE, data, result.error() + "\n" + data, ToolFailureType.TOOL_INTERNAL_ERROR,
                                    Map.of(), ToolActions.none());
                    return ToolBeforeResult.shortCircuit(output);
                } catch (TimeoutException ignored) { }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolBeforeResult.shortCircuit(ToolResult.failure(ToolFailureType.CANCELLED, "本机操作已取消"));
        } catch (ExecutionException e) {
            return pause("本机操作结果不可用，请检查后继续。", call);
        } finally {
            c.pending.remove(operation.id());
            c.queue.remove(pending);
        }
    }
    private static Object formatResult(String toolName, Map<String, Object> output, Object data) {
        if (!toolName.equals("file_read") || !(data instanceof Map<?, ?> read)) return output;
        return "[workspace] " + output.get("workspace") + "\n[file_read_metadata]\nformat=linear\npath="
                + String.valueOf(read.get("path")).replace('\n', ' ').replace('\r', ' ')
                + "\npage=0\ntotalPages=0\noffset=" + read.get("offset")
                + "\ntotalChars=" + read.get("totalChars") + "\nhasMore=" + read.get("hasMore")
                + "\nnextPage=0\nnextOffset=" + read.get("nextOffset")
                + "\ntruncated=" + read.get("truncated") + "\n[/file_read_metadata]\n[content]\n" + read.get("content");
    }
    private static ToolBeforeResult pause(String message, ToolCall call) {
        return ToolBeforeResult.shortCircuit(ToolResult.pending(new PendingAction(UUID.randomUUID().toString(),
                PendingActionType.HUMAN_INPUT, "本机工作区已暂停", message,
                Map.of("toolName", call.toolName(), "arguments", call.arguments(), "workspaceReconnect", true))));
    }
}
