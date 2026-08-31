package com.github.agentos.server.automation;

import com.github.agentos.server.automation.AutomationModels.AutomationClient;
import com.github.agentos.server.automation.AutomationModels.AutomationExecution;
import com.github.agentos.server.automation.AutomationModels.AutomationTask;
import com.github.agentos.server.automation.AutomationModels.ExecutionStatus;
import com.github.agentos.server.automation.AutomationModels.TriggerSource;
import com.github.agentos.server.automation.AutomationModels.TriggerDefinition;
import com.github.agentos.server.persistence.mybatis.AutomationClientMapper;
import com.github.agentos.server.persistence.mybatis.AutomationExecutionMapper;
import com.github.agentos.server.persistence.mybatis.AutomationTaskMapper;
import com.github.agentos.server.persistence.mybatis.PersistenceRows;
import org.springframework.dao.DuplicateKeyException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 自动化领域存储；正式模式使用 PostgreSQL，memory 模式用于测试和演示。 */
public final class AutomationStore {

    private final boolean persistent;
    private final AutomationTaskMapper taskMapper;
    private final AutomationExecutionMapper executionMapper;
    private final AutomationClientMapper clientMapper;
    private final ObjectMapper objectMapper;
    private final Map<String, AutomationTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, AutomationExecution> executions = new ConcurrentHashMap<>();
    private final Map<String, AutomationClient> clients = new ConcurrentHashMap<>();

    public AutomationStore(
            String mode,
            AutomationTaskMapper taskMapper,
            AutomationExecutionMapper executionMapper,
            AutomationClientMapper clientMapper,
            ObjectMapper objectMapper) {
        this.persistent = mode != null && ("postgresql".equalsIgnoreCase(mode.trim())
                || "postgres".equalsIgnoreCase(mode.trim()));
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.clientMapper = clientMapper;
        this.objectMapper = objectMapper;
    }

    public List<AutomationTask> tasks(String teamId, String userId) {
        if (!persistent) {
            return tasks.values().stream()
                    .filter(task -> owner(task.teamId(), task.userId(), teamId, userId))
                    .filter(task -> task.deletedAt() == null)
                    .sorted(Comparator.comparing(AutomationTask::updatedAt).reversed())
                    .toList();
        }
        return taskMapper.selectList(new QueryWrapper<PersistenceRows.AutomationTaskRow>()
                        .eq("team_id", teamId).eq("user_id", userId).isNull("deleted_at")
                        .orderByDesc("updated_at"))
                .stream().map(this::task).toList();
    }

    public Optional<AutomationTask> task(String id, String teamId, String userId) {
        AutomationTask value = persistent ? task(taskMapper.selectById(id)) : tasks.get(id);
        return value == null || value.deletedAt() != null
                || !owner(value.teamId(), value.userId(), teamId, userId)
                ? Optional.empty() : Optional.of(value);
    }

    public List<AutomationTask> due(Instant now, int limit) {
        if (!persistent) {
            return tasks.values().stream().filter(AutomationTask::enabled)
                    .filter(task -> task.deletedAt() == null && task.nextTriggerAt() != null
                            && !task.nextTriggerAt().isAfter(now))
                    .sorted(Comparator.comparing(AutomationTask::nextTriggerAt)).limit(limit).toList();
        }
        return taskMapper.selectList(new QueryWrapper<PersistenceRows.AutomationTaskRow>()
                        .eq("enabled", true).isNull("deleted_at").isNotNull("next_trigger_at")
                        .le("next_trigger_at", now).orderByAsc("next_trigger_at").last("LIMIT " + limit))
                .stream().map(this::task).toList();
    }

    public void save(AutomationTask task) {
        if (!persistent) {
            tasks.put(task.automationId(), task);
            return;
        }
        PersistenceRows.AutomationTaskRow row = row(task);
        if (taskMapper.updateById(row) == 0) taskMapper.insert(row);
    }

    public boolean createExecution(AutomationExecution execution) {
        if (!persistent) {
            if (executions.values().stream().anyMatch(item -> item.scheduledKey().equals(execution.scheduledKey()))) {
                return false;
            }
            executions.put(execution.executionId(), execution);
            return true;
        }
        try {
            executionMapper.insert(row(execution));
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public void save(AutomationExecution execution) {
        if (!persistent) executions.put(execution.executionId(), execution);
        else executionMapper.updateById(row(execution));
    }

    public Optional<AutomationExecution> execution(String id, String teamId, String userId) {
        AutomationExecution value = persistent ? toExecution(executionMapper.selectById(id)) : executions.get(id);
        return value == null || !owner(value.teamId(), value.userId(), teamId, userId)
                ? Optional.empty() : Optional.of(value);
    }

    public List<AutomationExecution> executions(
            String teamId, String userId, String automationId, String status, int offset, int limit) {
        if (!persistent) {
            return executions.values().stream()
                    .filter(item -> owner(item.teamId(), item.userId(), teamId, userId))
                    .filter(item -> automationId == null || automationId.isBlank()
                            || item.automationId().equals(automationId))
                    .filter(item -> status == null || status.isBlank() || item.status().name().equals(status))
                    .sorted(Comparator.comparing(AutomationExecution::scheduledAt).reversed())
                    .skip(offset).limit(limit).toList();
        }
        QueryWrapper<PersistenceRows.AutomationExecutionRow> query = executionQuery(
                teamId, userId, automationId, status).orderByDesc("scheduled_at")
                .last("LIMIT " + limit + " OFFSET " + offset);
        return executionMapper.selectList(query).stream().map(AutomationStore::toExecution).toList();
    }

    public long executionCount(String teamId, String userId, String automationId, String status) {
        if (!persistent) {
            return executions.values().stream()
                    .filter(item -> owner(item.teamId(), item.userId(), teamId, userId))
                    .filter(item -> automationId == null || automationId.isBlank()
                            || item.automationId().equals(automationId))
                    .filter(item -> status == null || status.isBlank() || item.status().name().equals(status))
                    .count();
        }
        return executionMapper.selectCount(executionQuery(teamId, userId, automationId, status));
    }

    public boolean hasActive(String automationId) {
        List<String> active = List.of("QUEUED", "CLAIMED", "RUNNING");
        if (!persistent) return executions.values().stream().anyMatch(item ->
                item.automationId().equals(automationId) && active.contains(item.status().name()));
        return executionMapper.selectCount(new QueryWrapper<PersistenceRows.AutomationExecutionRow>()
                .eq("automation_id", automationId).in("status", active)) > 0;
    }

    public Optional<AutomationExecution> claim(
            String clientId, String teamId, String userId, Instant now, Instant leaseExpiresAt) {
        List<AutomationExecution> queued;
        if (!persistent) {
            queued = executions.values().stream()
                    .filter(item -> owner(item.teamId(), item.userId(), teamId, userId))
                    .filter(item -> item.desktopClientId().equals(clientId)
                            && item.status() == ExecutionStatus.QUEUED)
                    .sorted(Comparator.comparing(AutomationExecution::scheduledAt)).toList();
            if (queued.isEmpty()) return Optional.empty();
            AutomationExecution claimed = withClaim(queued.get(0), now, leaseExpiresAt);
            if (!executions.replace(claimed.executionId(), queued.get(0), claimed)) return Optional.empty();
            return Optional.of(claimed);
        }
        queued = executionMapper.selectList(new QueryWrapper<PersistenceRows.AutomationExecutionRow>()
                        .eq("team_id", teamId).eq("user_id", userId)
                        .eq("desktop_client_id", clientId).eq("status", "QUEUED")
                        .orderByAsc("scheduled_at").last("LIMIT 5"))
                .stream().map(AutomationStore::toExecution).toList();
        for (AutomationExecution candidate : queued) {
            if (executionMapper.claim(candidate.executionId(), now, leaseExpiresAt) > 0) {
                return Optional.of(withClaim(candidate, now, leaseExpiresAt));
            }
        }
        return Optional.empty();
    }

    public List<AutomationExecution> expiredClaims(Instant now) {
        if (!persistent) return executions.values().stream()
                .filter(item -> item.status() == ExecutionStatus.CLAIMED
                        && item.leaseExpiresAt() != null && item.leaseExpiresAt().isBefore(now)).toList();
        return executionMapper.selectList(new QueryWrapper<PersistenceRows.AutomationExecutionRow>()
                        .eq("status", "CLAIMED").lt("lease_expires_at", now))
                .stream().map(AutomationStore::toExecution).toList();
    }

    public void saveClient(AutomationClient client) {
        if (!persistent) clients.put(client.clientKey(), client);
        else {
            PersistenceRows.AutomationClientRow row = row(client);
            if (clientMapper.updateById(row) == 0) clientMapper.insert(row);
        }
    }

    public Optional<AutomationClient> client(String clientId, String teamId, String userId) {
        String key = clientKey(teamId, userId, clientId);
        AutomationClient value = persistent ? client(clientMapper.selectById(key)) : clients.get(key);
        return Optional.ofNullable(value);
    }

    public static String clientKey(String teamId, String userId, String clientId) {
        return teamId + ":" + userId + ":" + clientId;
    }

    private QueryWrapper<PersistenceRows.AutomationExecutionRow> executionQuery(
            String teamId, String userId, String automationId, String status) {
        QueryWrapper<PersistenceRows.AutomationExecutionRow> query =
                new QueryWrapper<PersistenceRows.AutomationExecutionRow>()
                        .eq("team_id", teamId).eq("user_id", userId);
        if (automationId != null && !automationId.isBlank()) query.eq("automation_id", automationId);
        if (status != null && !status.isBlank()) query.eq("status", status);
        return query;
    }

    private AutomationTask task(PersistenceRows.AutomationTaskRow row) {
        if (row == null) return null;
        try {
            return new AutomationTask(row.automationId(), row.teamId(), row.userId(), row.name(),
                    row.prompt(), row.agentId(), row.modelId(), row.approvalMode(),
                    row.desktopClientId(), row.workspaceId(), row.workspaceName(),
                    objectMapper.readValue(row.triggerPayload(), TriggerDefinition.class), row.enabled(),
                    row.nextTriggerAt(), row.lastTriggerAt(), row.deletedAt(), row.createdAt(),
                    row.updatedAt(), row.version());
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法解析自动化触发规则", exception);
        }
    }

    private PersistenceRows.AutomationTaskRow row(AutomationTask task) {
        try {
            return new PersistenceRows.AutomationTaskRow(task.automationId(), task.teamId(), task.userId(),
                    task.name(), task.prompt(), task.agentId(), task.modelId(), task.approvalMode(),
                    task.desktopClientId(), task.workspaceId(), task.workspaceName(),
                    objectMapper.writeValueAsString(task.trigger()), task.enabled(), task.nextTriggerAt(),
                    task.lastTriggerAt(), task.deletedAt(), task.createdAt(), task.updatedAt(), task.version());
        } catch (JacksonException exception) {
            throw new IllegalStateException("无法编码自动化触发规则", exception);
        }
    }

    private static AutomationExecution toExecution(PersistenceRows.AutomationExecutionRow row) {
        return row == null ? null : new AutomationExecution(row.executionId(), row.automationId(),
                row.teamId(), row.userId(), row.taskName(), TriggerSource.valueOf(row.triggerSource()),
                ExecutionStatus.valueOf(row.status()), row.scheduledKey(), row.scheduledAt(),
                row.desktopClientId(), row.workspaceId(), row.workspaceName(), row.claimedAt(),
                row.leaseExpiresAt(), row.sessionId(), row.runId(), row.invocationId(), row.startedAt(),
                row.finishedAt(), row.resultExcerpt(), row.errorMessage(), row.createdAt(),
                row.updatedAt(), row.version());
    }

    private static PersistenceRows.AutomationExecutionRow row(AutomationExecution value) {
        return new PersistenceRows.AutomationExecutionRow(value.executionId(), value.automationId(),
                value.teamId(), value.userId(), value.taskName(), value.triggerSource().name(),
                value.status().name(), value.scheduledKey(), value.scheduledAt(), value.desktopClientId(),
                value.workspaceId(), value.workspaceName(), value.claimedAt(), value.leaseExpiresAt(),
                text(value.sessionId()), text(value.runId()), text(value.invocationId()), value.startedAt(),
                value.finishedAt(), text(value.resultExcerpt()), text(value.errorMessage()), value.createdAt(),
                value.updatedAt(), value.version());
    }

    private static AutomationClient client(PersistenceRows.AutomationClientRow row) {
        return row == null ? null : new AutomationClient(row.clientKey(), row.clientId(), row.teamId(),
                row.userId(), row.platform(), row.appVersion(), row.lastSeenAt(), row.createdAt(), row.updatedAt());
    }

    private static PersistenceRows.AutomationClientRow row(AutomationClient value) {
        return new PersistenceRows.AutomationClientRow(value.clientKey(), value.clientId(), value.teamId(),
                value.userId(), value.platform(), value.appVersion(), value.lastSeenAt(),
                value.createdAt(), value.updatedAt());
    }

    private static AutomationExecution withClaim(AutomationExecution value, Instant now, Instant lease) {
        return new AutomationExecution(value.executionId(), value.automationId(), value.teamId(),
                value.userId(), value.taskName(), value.triggerSource(), ExecutionStatus.CLAIMED,
                value.scheduledKey(), value.scheduledAt(), value.desktopClientId(), value.workspaceId(),
                value.workspaceName(), now, lease, value.sessionId(), value.runId(), value.invocationId(),
                value.startedAt(), value.finishedAt(), value.resultExcerpt(), value.errorMessage(),
                value.createdAt(), now, value.version() + 1);
    }

    private static boolean owner(String leftTeam, String leftUser, String team, String user) {
        return leftTeam.equals(team) && leftUser.equals(user);
    }

    private static String text(String value) { return value == null ? "" : value; }
}
