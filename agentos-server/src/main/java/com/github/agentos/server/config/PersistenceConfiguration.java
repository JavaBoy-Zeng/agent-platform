package com.github.agentos.server.config;

import com.github.agentos.agent.loop.ContinuationStore;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.CheckpointStore;
import com.github.agentos.kernel.InMemoryAgentEventStore;
import com.github.agentos.kernel.InMemoryCheckpointStore;
import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.kernel.SessionService;
import com.github.agentos.server.persistence.mybatis.AgentEventMapper;
import com.github.agentos.server.persistence.mybatis.AgentRunMapper;
import com.github.agentos.server.persistence.mybatis.AgentStreamEventMapper;
import com.github.agentos.server.persistence.mybatis.CheckpointMapper;
import com.github.agentos.server.persistence.mybatis.ContinuationMapper;
import com.github.agentos.server.persistence.mybatis.MybatisRuntimeStores;
import com.github.agentos.server.persistence.mybatis.MybatisAgentRunStore;
import com.github.agentos.server.run.AgentRunStore;
import com.github.agentos.server.run.InMemoryAgentRunStore;
import com.github.agentos.server.persistence.mybatis.SessionMapper;
import com.github.agentos.server.persistence.mybatis.SettingsMapper;
import com.github.agentos.server.persistence.mybatis.UsageMapper;
import com.github.agentos.server.persistence.mybatis.UserMapper;
import com.github.agentos.server.settings.SettingsService;
import com.github.agentos.server.usage.UsageStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * 运行态持久化装配。
 *
 * <p>{@code postgresql} 是正式运行模式，全部状态通过 MyBatis-Plus 写入
 * PostgreSQL；{@code memory} 仅用于测试和无数据库演示。</p>
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    /** 事件存储：正式环境写 PostgreSQL，测试环境留在进程内。 */
    @Bean
    AgentEventStore agentEventStore(
            @Value("${agentos.persistence.mode:postgresql}") String mode,
            ObjectProvider<AgentEventMapper> mapper,
            ObjectMapper objectMapper,
            @Value("${agentos.runtime.retention.max-invocations:10000}") int maxInvocations,
            @Value("${agentos.runtime.retention.max-sessions:5000}") int maxSessions,
            @Value("${agentos.runtime.retention.max-events-per-invocation:200}")
            int maxEventsPerInvocation,
            @Value("${agentos.runtime.retention.max-events-per-session:2000}")
            int maxEventsPerSession) {
        if (isPostgresql(mode)) {
            return new MybatisRuntimeStores.EventStore(mapper.getObject(), objectMapper);
        }
        requireMemory(mode);
        return new InMemoryAgentEventStore(
                maxInvocations, maxSessions,
                maxEventsPerInvocation, maxEventsPerSession);
    }

    /** Run 快照和关键用户流事件：status 与 token delta 不进入该存储。 */
    @Bean
    AgentRunStore agentRunStore(
            @Value("${agentos.persistence.mode:postgresql}") String mode,
            ObjectProvider<AgentRunMapper> runMapper,
            ObjectProvider<AgentStreamEventMapper> streamEventMapper,
            ObjectMapper objectMapper) {
        if (isPostgresql(mode)) {
            return new MybatisAgentRunStore(
                    runMapper.getObject(), streamEventMapper.getObject(), objectMapper);
        }
        requireMemory(mode);
        return new InMemoryAgentRunStore();
    }

    /** 审批恢复 Checkpoint 存储。 */
    @Bean
    CheckpointStore checkpointStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<CheckpointMapper> mapper,
            ObjectMapper objectMapper) {
        if (isPostgresql(mode)) {
            return new MybatisRuntimeStores.Checkpoints(mapper.getObject(), objectMapper);
        }
        requireMemory(mode);
        return new InMemoryCheckpointStore();
    }

    /** 主 Agent 断点续跑状态存储。 */
    @Bean
    ContinuationStore continuationStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<ContinuationMapper> mapper,
            ObjectMapper objectMapper) {
        if (isPostgresql(mode)) {
            return new MybatisRuntimeStores.Continuations(mapper.getObject(), objectMapper);
        }
        requireMemory(mode);
        return ContinuationStore.NOOP;
    }

    /** 用量账本存储。 */
    @Bean
    UsageStore usageStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<UsageMapper> mapper) {
        if (isPostgresql(mode)) {
            return new MybatisRuntimeStores.Usages(mapper.getObject());
        }
        requireMemory(mode);
        return new UsageStore.InMemoryUsageStore();
    }

    /** 会话服务。 */
    @Bean
    SessionService sessionService(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<SessionMapper> mapper,
            ObjectMapper objectMapper) {
        if (isPostgresql(mode)) {
            return new MybatisRuntimeStores.Sessions(mapper.getObject(), objectMapper);
        }
        requireMemory(mode);
        return new InMemorySessionService();
    }

    /** 登录用户存储。 */
    @Bean
    com.github.agentos.server.security.UserStore userStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<UserMapper> mapper) {
        if (isPostgresql(mode)) {
            return new MybatisRuntimeStores.Users(mapper.getObject());
        }
        requireMemory(mode);
        return new com.github.agentos.server.security.InMemoryUserStore();
    }

    /** 全局运行时配置（key/value）。 */
    @Bean
    SettingsService settingsService(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<SettingsMapper> mapper) {
        if (isPostgresql(mode)) {
            return new MybatisRuntimeStores.Settings(mapper.getObject());
        }
        requireMemory(mode);
        return new SettingsService.InMemorySettingsService();
    }

    private static boolean isPostgresql(String mode) {
        if (mode == null) return false;
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return "postgresql".equals(normalized) || "postgres".equals(normalized);
    }

    private static void requireMemory(String mode) {
        if (mode == null || !"memory".equals(mode.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "agentos.persistence.mode must be one of: postgresql, memory");
        }
    }
}
