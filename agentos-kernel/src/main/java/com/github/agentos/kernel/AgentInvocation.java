package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次独立 Agent 执行的可观察运行记录。
 *
 * <p>Invocation 与 Session 相互独立：同一 Session 的每次运行都会创建新的 Invocation。</p>
 */
public final class AgentInvocation {

    private final String invocationId;
    private final String sessionId;
    private final String agentId;
    private final String taskId;
    private final Instant startedAt;
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final AtomicInteger replans = new AtomicInteger();
    private final AtomicInteger steps = new AtomicInteger();
    private volatile Instant finishedAt;
    private volatile AgentRunStatus status;
    private volatile Throwable error;

    /** 创建尚未进入执行循环的 Invocation。 */
    public AgentInvocation(
            String invocationId, String sessionId, String agentId, String taskId, Instant startedAt) {
        this.invocationId = requireText(invocationId, "invocationId");
        this.sessionId = requireText(sessionId, "sessionId");
        this.agentId = requireText(agentId, "agentId");
        this.taskId = taskId == null ? "" : taskId.trim();
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.status = AgentRunStatus.CREATED;
    }

    /** 标记执行循环已经开始。 */
    public void start() {
        status = AgentRunStatus.RUNNING;
    }

    /** 根据最终会话状态结束本次 Invocation。 */
    public void finish(AgentState state) {
        Objects.requireNonNull(state, "state must not be null");
        status = switch (state.status()) {
            case COMPLETED -> AgentRunStatus.COMPLETED;
            case CANCELLED -> AgentRunStatus.CANCELLED;
            case FAILED -> AgentRunStatus.FAILED;
            default -> AgentRunStatus.FAILED;
        };
        if (status == AgentRunStatus.FAILED && !state.error().isBlank()) {
            error = new IllegalStateException(state.error());
        }
        finishedAt = Instant.now();
    }

    /** 以未捕获异常结束本次 Invocation。 */
    public void fail(Throwable cause) {
        error = Objects.requireNonNull(cause, "cause must not be null");
        status = AgentRunStatus.FAILED;
        finishedAt = Instant.now();
    }

    public String invocationId() { return invocationId; }
    public String sessionId() { return sessionId; }
    public String agentId() { return agentId; }
    public String taskId() { return taskId; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public AgentRunStatus status() { return status; }
    public Throwable error() { return error; }
    public int modelCalls() { return modelCalls.get(); }
    public int toolCalls() { return toolCalls.get(); }
    public int replans() { return replans.get(); }
    public int steps() { return steps.get(); }
    public int incrementModelCalls() { return modelCalls.incrementAndGet(); }
    public int incrementToolCalls() { return toolCalls.incrementAndGet(); }
    public int incrementReplans() { return replans.incrementAndGet(); }
    public int incrementSteps() { return steps.incrementAndGet(); }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
