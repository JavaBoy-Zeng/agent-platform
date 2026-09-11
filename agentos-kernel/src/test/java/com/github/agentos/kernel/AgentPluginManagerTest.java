package com.github.agentos.kernel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 插件管理器分发与异常隔离测试。 */
class AgentPluginManagerTest {

    @Test
    void dispatchesUsageAndEventsToAllPluginsInOrder() {
        RecordingPlugin first = new RecordingPlugin("first");
        RecordingPlugin second = new RecordingPlugin("second");
        AgentPluginManager manager = AgentPluginManager.of(first, second);

        manager.onModelUsage("s1", new ModelUsage("model-a", 10, 5));
        manager.publish(DefaultAgentEvent.of(
                boundContext(), AgentEventType.AGENT_STARTED, "objective", java.util.Map.of()));

        assertThat(first.calls).containsExactly("usage:s1", "event:AGENT_STARTED");
        assertThat(second.calls).containsExactly("usage:s1", "event:AGENT_STARTED");
    }

    @Test
    void isolatesPluginExceptionsAndContinuesWithRemainingPlugins() {
        RecordingPlugin healthy = new RecordingPlugin("healthy");
        AgentPlugin failing = new AgentPlugin() {
            @Override
            public String name() {
                return "failing";
            }

            @Override
            public void onModelUsage(String sessionId, ModelUsage usage) {
                throw new IllegalStateException("plugin exploded");
            }
        };
        AgentPluginManager manager = AgentPluginManager.of(failing, healthy);

        manager.onModelUsage("s1", new ModelUsage("model-a", 1, 1));

        assertThat(healthy.calls).containsExactly("usage:s1");
    }

    @Test
    void dispatchesRunLifecycleHooks() {
        RecordingPlugin plugin = new RecordingPlugin("recorder");
        AgentPluginManager manager = AgentPluginManager.of(plugin);
        AgentRequest request = AgentRequest.of("s1", "objective");
        InvocationContext context = InvocationContext.of("plan-execute-agent");
        AgentState completed = AgentState.ready().complete("done");

        manager.beforeRun(request, context);
        manager.afterRun(request, context, completed);
        manager.onRunError(request, context, new IllegalStateException("boom"));

        assertThat(plugin.calls).containsExactly(
                "beforeRun", "afterRun:COMPLETED", "onRunError");
    }

    @Test
    void emptyManagerPublishesWithoutSideEffects() {
        AgentPluginManager manager = AgentPluginManager.empty();

        manager.publish(DefaultAgentEvent.of(
                boundContext(), AgentEventType.AGENT_STARTED, "objective", java.util.Map.of()));
        manager.onModelUsage("s1", new ModelUsage("model-a", 1, 1));
    }

    /** 领域事件要求上下文绑定 Invocation；构造最小可用绑定上下文。 */
    private static InvocationContext boundContext() {
        AgentInvocation invocation = new AgentInvocation(
                "invocation-1", "s1", "plan-execute-agent", "", Instant.now());
        return InvocationContext.of("plan-execute-agent")
                .withRuntime(invocation, AgentEventPublisher.NOOP);
    }

    private static final class RecordingPlugin implements AgentPlugin {
        private final String name;
        final List<String> calls = new ArrayList<>();

        RecordingPlugin(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void beforeRun(AgentRequest request, InvocationContext context) {
            calls.add("beforeRun");
        }

        @Override
        public void afterRun(
                AgentRequest request, InvocationContext context, AgentState result) {
            calls.add("afterRun:" + result.status());
        }

        @Override
        public void onRunError(
                AgentRequest request, InvocationContext context, Exception error) {
            calls.add("onRunError");
        }

        @Override
        public void onModelUsage(String sessionId, ModelUsage usage) {
            calls.add("usage:" + sessionId);
        }

        @Override
        public void onEvent(AgentEvent event) {
            calls.add("event:" + event.type());
        }
    }
}
