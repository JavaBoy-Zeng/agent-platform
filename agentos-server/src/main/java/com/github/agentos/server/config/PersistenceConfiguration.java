package com.github.agentos.server.config;

import com.github.agentos.agent.loop.ContinuationStore;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.CheckpointStore;
import com.github.agentos.kernel.InMemoryAgentEventStore;
import com.github.agentos.kernel.InMemoryCheckpointStore;
import com.github.agentos.kernel.InMemorySessionService;
import com.github.agentos.kernel.SessionService;
import com.github.agentos.server.persistence.SqliteAgentEventStore;
import com.github.agentos.server.persistence.SqliteCheckpointStore;
import com.github.agentos.server.persistence.SqliteContinuationStore;
import com.github.agentos.server.persistence.SqliteSessionService;
import com.github.agentos.server.persistence.SqliteUsageStore;
import com.github.agentos.server.usage.UsageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 运行态持久化装配。
 *
 * <p>{@code agentos.persistence.mode=memory}（默认）时全部状态留在进程内，
 * 零依赖启动；{@code sqlite} 时领域事件、审批 Checkpoint、断点续跑状态与
 * 用量账本写入同一 SQLite 文件，重启后审批恢复与账本查询仍可用。</p>
 */
@Configuration(proxyBeanMethods = false)
public class PersistenceConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersistenceConfiguration.class);

    /** sqlite 模式下才创建 DataSource；父目录不存在时自动创建。 */
    @Bean
    @ConditionalOnProperty(name = "agentos.persistence.mode", havingValue = "sqlite")
    DataSource sqliteDataSource(
            @Value("${agentos.persistence.sqlite-file:.agentos/runtime/runtime.sqlite}") String file)
            throws java.io.IOException {
        Path databaseFile = Path.of(file).toAbsolutePath().normalize();
        if (databaseFile.getParent() != null) {
            Files.createDirectories(databaseFile.getParent());
        }
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + databaseFile);
        LOGGER.info("[persistence] sqlite mode enabled file={}", databaseFile);
        return dataSource;
    }

    /** 事件存储：sqlite 落库，memory 留在进程内。 */
    @Bean
    AgentEventStore agentEventStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<DataSource> dataSource,
            ObjectMapper objectMapper,
            @Value("${agentos.runtime.retention.max-invocations:10000}") int maxInvocations,
            @Value("${agentos.runtime.retention.max-sessions:5000}") int maxSessions,
            @Value("${agentos.runtime.retention.max-events-per-invocation:200}")
            int maxEventsPerInvocation,
            @Value("${agentos.runtime.retention.max-events-per-session:2000}")
            int maxEventsPerSession) {
        if (isSqlite(mode)) {
            return new SqliteAgentEventStore(dataSource.getObject(), objectMapper);
        }
        return new InMemoryAgentEventStore(
                maxInvocations, maxSessions,
                maxEventsPerInvocation, maxEventsPerSession);
    }

    /** 审批恢复 Checkpoint 存储：sqlite 落库，memory 留在进程内。 */
    @Bean
    CheckpointStore checkpointStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<DataSource> dataSource,
            ObjectMapper objectMapper) {
        if (isSqlite(mode)) {
            return new SqliteCheckpointStore(dataSource.getObject(), objectMapper);
        }
        return new InMemoryCheckpointStore();
    }

    /** 主 Agent 断点续跑状态存储：sqlite 落库，memory 退化为空实现。 */
    @Bean
    ContinuationStore continuationStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<DataSource> dataSource,
            ObjectMapper objectMapper) {
        if (isSqlite(mode)) {
            return new SqliteContinuationStore(dataSource.getObject(), objectMapper);
        }
        return ContinuationStore.NOOP;
    }

    /** 用量账本存储：sqlite 落库，memory 留在进程内。 */
    @Bean
    UsageStore usageStore(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<DataSource> dataSource) {
        if (isSqlite(mode)) {
            return new SqliteUsageStore(dataSource.getObject());
        }
        return new UsageStore.InMemoryUsageStore();
    }

    /** 会话服务：sqlite 落库，memory 留在进程内。 */
    @Bean
    SessionService sessionService(
            @Value("${agentos.persistence.mode:memory}") String mode,
            ObjectProvider<DataSource> dataSource,
            ObjectMapper objectMapper) {
        if (isSqlite(mode)) {
            return new SqliteSessionService(dataSource.getObject(), objectMapper);
        }
        return new InMemorySessionService();
    }

    private static boolean isSqlite(String mode) {
        return mode != null && "sqlite".equals(mode.trim().toLowerCase(Locale.ROOT));
    }
}
