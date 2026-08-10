package com.github.agentos.memory;

import java.time.Instant;
import java.util.Objects;

/** 可持久化、可恢复的 L1-L3 处理任务。 */
public record PipelineJob(
        String id,
        String turnId,
        MemoryScope scope,
        Stage stage,
        Status status,
        int attempts,
        String error,
        Instant updatedAt) {

    public PipelineJob {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (turnId == null || turnId.isBlank()) throw new IllegalArgumentException("turnId must not be blank");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        stage = Objects.requireNonNull(stage, "stage must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        if (attempts < 0) throw new IllegalArgumentException("attempts must not be negative");
        error = Objects.requireNonNullElse(error, "");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static PipelineJob pending(String turnId, MemoryScope scope) {
        return new PipelineJob("pipeline:" + turnId, turnId, scope, Stage.L1, Status.PENDING, 0, "", Instant.now());
    }

    public PipelineJob running() {
        return new PipelineJob(id, turnId, scope, stage, Status.RUNNING, attempts + 1, "", Instant.now());
    }

    public PipelineJob advance() {
        Stage next = switch (stage) {
            case L1 -> Stage.L2;
            case L2 -> Stage.L3;
            case L3, DONE -> Stage.DONE;
        };
        Status nextStatus = next == Stage.DONE ? Status.COMPLETED : Status.PENDING;
        return new PipelineJob(id, turnId, scope, next, nextStatus, attempts, "", Instant.now());
    }

    public PipelineJob failed(Throwable cause) {
        String message = cause == null ? "unknown pipeline failure"
                : Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
        return new PipelineJob(id, turnId, scope, stage, Status.FAILED, attempts, message, Instant.now());
    }

    public PipelineJob retry() {
        return new PipelineJob(id, turnId, scope, stage, Status.PENDING, attempts, error, Instant.now());
    }

    public enum Stage { L1, L2, L3, DONE }

    public enum Status { PENDING, RUNNING, FAILED, COMPLETED }
}
