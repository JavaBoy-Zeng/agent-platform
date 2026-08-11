package com.github.agentos.server;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 注册服务端正在执行的流式 Agent 任务，并允许按 sessionId 中断其真实执行线程。
 *
 * <p>虚拟线程和平台线程都使用 {@link Thread#interrupt()} 取消。任务代码仍需在模型调用、
 * 工具调用和循环边界协作检查中断状态。</p>
 */
public final class AgentRunTaskRegistry {

    private final ConcurrentMap<String, RunningTask> tasks = new ConcurrentHashMap<>();

    /**
     * 注册并提交任务。同一 session 同时只允许一个流式运行。
     *
     * @return 注册成功返回 {@code true}；已有运行返回 {@code false}
     */
    public boolean start(String sessionId, Executor executor, Runnable action) {
        requireSessionId(sessionId);
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(action, "action must not be null");
        RunningTask task = new RunningTask();
        if (tasks.putIfAbsent(sessionId, task) != null) {
            return false;
        }
        try {
            executor.execute(() -> {
                task.attach(Thread.currentThread());
                try {
                    action.run();
                } finally {
                    tasks.remove(sessionId, task);
                }
            });
            return true;
        } catch (RuntimeException exception) {
            tasks.remove(sessionId, task);
            throw exception;
        }
    }

    /** 设置取消标记并中断当前执行线程。 */
    public boolean cancel(String sessionId) {
        requireSessionId(sessionId);
        RunningTask task = tasks.get(sessionId);
        return task != null && task.cancel();
    }

    /** 返回指定会话是否仍由注册表管理。 */
    public boolean isRunning(String sessionId) {
        requireSessionId(sessionId);
        return tasks.containsKey(sessionId);
    }

    private static void requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
    }

    private static final class RunningTask {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Thread thread;

        void attach(Thread runningThread) {
            thread = runningThread;
            if (cancelled.get()) {
                runningThread.interrupt();
            }
        }

        boolean cancel() {
            boolean firstRequest = cancelled.compareAndSet(false, true);
            Thread runningThread = thread;
            if (runningThread != null) {
                runningThread.interrupt();
            }
            return firstRequest || runningThread != null;
        }
    }
}
