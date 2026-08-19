package com.github.agentos.kernel;

/**
 * 在事件转发前把 {@link EventActions#stateDelta()} 合并进会话状态的责任发布器。
 *
 * <p>“事件 = 发生了什么 + 状态应该怎么改变”：业务方只发布携带动作的事件，
 * 状态合并由 Runtime 统一完成，合并失败不得阻断事件转发。</p>
 */
final class StateMergingEventPublisher implements AgentEventPublisher {

    private final AgentEventPublisher delegate;
    private final SessionService sessionService;

    StateMergingEventPublisher(AgentEventPublisher delegate, SessionService sessionService) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate must not be null");
        this.sessionService = java.util.Objects.requireNonNull(
                sessionService, "sessionService must not be null");
    }

    @Override
    public void publish(AgentEvent event) {
        mergeQuietly(event);
        delegate.publish(event);
    }

    private void mergeQuietly(AgentEvent event) {
        try {
            EventActions actions = event.actions();
            if (actions != null && !actions.stateDelta().isEmpty()) {
                sessionService.applyDelta(event.sessionId(), actions.stateDelta());
            }
        } catch (RuntimeException ignored) {
            // 状态合并属于 Runtime 内务，不得影响观察端收到事件。
        }
    }
}
