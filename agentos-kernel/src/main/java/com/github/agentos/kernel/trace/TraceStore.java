package com.github.agentos.kernel.trace;

import java.util.List;

/**
 * Span 存储接口：追加 Span 并按 Trace 或 Session 查询。
 *
 * <p>与 {@link com.github.agentos.kernel.AgentEventStore} 保持一致的查询契约，
 * 让 Trace 消费端按时间顺序回放同一 Invocation 的全部 Span。</p>
 */
public interface TraceStore {

    /** 追加一条不可变 Span。 */
    void append(Span span);

    /** 按 Trace 标识（invocationId）返回时间顺序一致的完整 Span 列表。 */
    List<Span> findByTraceId(String traceId);

    /** 按 Session 标识返回该会话全部 Trace 的 Span 列表。 */
    List<Span> findBySessionId(String sessionId);
}
