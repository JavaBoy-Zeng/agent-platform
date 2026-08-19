package com.github.agentos.kernel;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次 Invocation 的协作式取消令牌。
 *
 * <p>取代散落各处的 {@code Thread.interrupted()} 检查：Runner 在执行边界创建令牌并
 * 注入 {@link InvocationContext}，执行链（规划器、计划执行器、工具、直答 Agent）
 * 在协作点检查令牌状态；外部通过 {@link AgentRunner#cancel(String, String)}
 * 请求取消。线程中断仍作为阻塞 I/O 的兜底通道与令牌并行生效。</p>
 *
 * <p>令牌只记录首次取消原因，重复取消不覆盖。线程安全。</p>
 */
public final class CancellationToken {

    private final AtomicReference<String> reason = new AtomicReference<>();

    private CancellationToken() {
    }

    /** 创建未取消的新令牌。 */
    public static CancellationToken notCancelled() {
        return new CancellationToken();
    }

    /** 返回是否已请求取消。 */
    public boolean isCancelled() {
        return reason.get() != null;
    }

    /** 返回首次取消原因；未取消时为空。 */
    public Optional<String> reason() {
        return Optional.ofNullable(reason.get());
    }

    /** 请求取消；仅记录首次原因，后续调用静默忽略。 */
    public void cancel(String cancelReason) {
        String text = cancelReason == null || cancelReason.isBlank()
                ? "cancelled" : cancelReason.trim();
        reason.compareAndSet(null, text);
    }

    /** 已取消时抛出携带原因的 {@link CancellationException}。 */
    public void throwIfCancelled() {
        String current = reason.get();
        if (current != null) {
            throw new CancellationException(current);
        }
    }

    @Override
    public String toString() {
        String current = reason.get();
        return current == null ? "CancellationToken[active]" : "CancellationToken[" + current + "]";
    }

    /** 供测试断言使用；语义与 {@link #isCancelled()} 一致。 */
    boolean sameReason(String expected) {
        return Objects.equals(reason.get(), expected);
    }
}
