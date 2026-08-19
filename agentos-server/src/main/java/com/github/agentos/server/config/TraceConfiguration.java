package com.github.agentos.server.config;

import com.github.agentos.kernel.AgentPlugin;
import com.github.agentos.kernel.trace.InMemoryTraceStore;
import com.github.agentos.kernel.trace.TraceRecorder;
import com.github.agentos.kernel.trace.TraceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 链路追踪 Spring 装配。
 *
 * <p>提供 {@link TraceStore}（内存版）与 {@link TraceRecorder}（AgentPlugin），
 * TraceRecorder 被 {@code agentPluginManager(List<AgentPlugin>)} 自动收集，
 * 在 Runner 执行边界接收事件并构建 Span。</p>
 */
@Configuration(proxyBeanMethods = false)
public class TraceConfiguration {

    /** 创建内存版 TraceStore；后续可替换为持久化实现。 */
    @Bean
    @ConditionalOnMissingBean
    TraceStore traceStore() {
        return new InMemoryTraceStore();
    }

    /** 创建 TraceRecorder 插件，接入 Runner 生命周期。 */
    @Bean
    AgentPlugin traceRecorder(TraceStore traceStore) {
        return new TraceRecorder(traceStore);
    }
}
