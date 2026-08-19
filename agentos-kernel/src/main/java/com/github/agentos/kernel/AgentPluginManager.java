package com.github.agentos.kernel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 插件装配与安全分发器。
 *
 * <p>按声明顺序把生命周期钩子分发给全部插件；单个插件抛出的异常被记录并隔离，
 * 不影响后续插件与执行链本身。同时实现 {@link AgentEventPublisher}，
 * 可直接挂进 Runner 的事件发布组合，让插件观察到全部领域事件。</p>
 */
public final class AgentPluginManager implements AgentEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentPluginManager.class);

    private final List<AgentPlugin> plugins;

    private AgentPluginManager(List<AgentPlugin> plugins) {
        this.plugins = List.copyOf(plugins);
        this.plugins.forEach(plugin -> Objects.requireNonNull(
                plugin, "plugin must not be null"));
    }

    /** 创建按序装配的插件管理器。 */
    public static AgentPluginManager of(AgentPlugin... plugins) {
        return new AgentPluginManager(List.of(plugins));
    }

    /** 创建按序装配的插件管理器。 */
    public static AgentPluginManager of(List<AgentPlugin> plugins) {
        return new AgentPluginManager(plugins);
    }

    /** 创建不含任何插件的管理器。 */
    public static AgentPluginManager empty() {
        return new AgentPluginManager(List.of());
    }

    /** 分发执行开始钩子。 */
    public void beforeRun(AgentRequest request, InvocationContext context) {
        for (AgentPlugin plugin : plugins) {
            try {
                plugin.beforeRun(request, context);
            } catch (RuntimeException exception) {
                LOGGER.warn("plugin {} failed in beforeRun", plugin.name(), exception);
            }
        }
    }

    /** 分发执行结束钩子。 */
    public void afterRun(AgentRequest request, InvocationContext context, AgentState result) {
        for (AgentPlugin plugin : plugins) {
            try {
                plugin.afterRun(request, context, result);
            } catch (RuntimeException exception) {
                LOGGER.warn("plugin {} failed in afterRun", plugin.name(), exception);
            }
        }
    }

    /** 分发执行异常钩子。 */
    public void onRunError(AgentRequest request, InvocationContext context, Exception error) {
        for (AgentPlugin plugin : plugins) {
            try {
                plugin.onRunError(request, context, error);
            } catch (RuntimeException exception) {
                LOGGER.warn("plugin {} failed in onRunError", plugin.name(), exception);
            }
        }
    }

    /** 分发模型用量钩子。 */
    public void onModelUsage(String sessionId, ModelUsage usage) {
        for (AgentPlugin plugin : plugins) {
            try {
                plugin.onModelUsage(sessionId, usage);
            } catch (RuntimeException exception) {
                LOGGER.warn("plugin {} failed in onModelUsage", plugin.name(), exception);
            }
        }
    }

    /** 分发领域事件钩子；实现自 {@link AgentEventPublisher}，供事件组合挂载。 */
    @Override
    public void publish(AgentEvent event) {
        for (AgentPlugin plugin : plugins) {
            try {
                plugin.onEvent(event);
            } catch (RuntimeException exception) {
                LOGGER.warn("plugin {} failed in onEvent", plugin.name(), exception);
            }
        }
    }
}
