package com.github.agentos.planner;

import com.github.agentos.kernel.ModelUsage;

/**
 * 模型用量监听器。
 *
 * <p>由模型客户端在每次成功调用后回调，供记账、日志或事件流消费。
 * 实现必须快速返回，不得抛出异常中断模型调用链。</p>
 */
@FunctionalInterface
public interface ModelUsageListener {

    /**
     * 记录一次模型调用的 token 用量。
     *
     * @param sessionId 会话标识
     * @param usage 本次调用用量；具体实现不应持有该引用
     */
    void onUsage(String sessionId, ModelUsage usage);
}
