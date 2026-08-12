package com.github.agentos.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/** 使用独立属性文件持久化每次 Invocation Checkpoint。 */
public final class FileCheckpointStore implements CheckpointStore {

    private static final String SUFFIX = ".checkpoint";
    private final Path directory;

    /** 创建存储并确保目标目录存在。 */
    public FileCheckpointStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.directory);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create checkpoint directory", exception);
        }
    }

    /** 以临时文件替换方式原子保存快照。 */
    @Override
    public synchronized void save(AgentCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        Properties properties = toProperties(checkpoint);
        Path target = path(checkpoint.invocationId());
        try {
            Path temporary = Files.createTempFile(directory, checkpoint.invocationId(), ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "AgentOS checkpoint");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to save checkpoint", exception);
        }
    }

    /** 从磁盘重新读取快照，文件不存在时返回空。 */
    @Override
    public synchronized Optional<AgentCheckpoint> load(String invocationId) {
        Path target = path(invocationId);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(target)) {
            properties.load(input);
            return Optional.of(fromProperties(properties));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("failed to load checkpoint: " + invocationId, exception);
        }
    }

    /** 删除已完成或已终止 Invocation 的快照。 */
    @Override
    public synchronized void delete(String invocationId) {
        try {
            Files.deleteIfExists(path(invocationId));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete checkpoint: " + invocationId, exception);
        }
    }

    private Path path(String invocationId) {
        if (invocationId == null || !invocationId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid invocationId");
        }
        return directory.resolve(invocationId + SUFFIX);
    }

    private static Properties toProperties(AgentCheckpoint checkpoint) {
        Properties value = new Properties();
        put(value, "sessionId", checkpoint.sessionId());
        put(value, "invocationId", checkpoint.invocationId());
        put(value, "agentId", checkpoint.agentId());
        put(value, "taskId", checkpoint.taskId());
        put(value, "teamId", checkpoint.teamId());
        put(value, "userId", checkpoint.userId());
        put(value, "objective", checkpoint.objective());
        put(value, "currentPlanId", checkpoint.currentPlanId());
        put(value, "currentStepId", checkpoint.currentStepId());
        put(value, "currentStepIndex", checkpoint.currentStepIndex());
        put(value, "completedStepCount", checkpoint.completedStepIds().size());
        for (int index = 0; index < checkpoint.completedStepIds().size(); index++) {
            put(value, "completedStep." + index, checkpoint.completedStepIds().get(index));
        }
        putMap(value, "state", checkpoint.state());
        PendingAction action = checkpoint.pendingAction();
        put(value, "pending.id", action.pendingActionId());
        put(value, "pending.type", action.type().name());
        put(value, "pending.title", action.title());
        put(value, "pending.description", action.description());
        Map<String, String> payload = new LinkedHashMap<>();
        action.payload().forEach((key, item) -> payload.put(key, String.valueOf(item)));
        putMap(value, "pending.payload", payload);
        put(value, "counters.modelCalls", checkpoint.executionCounters().modelCalls());
        put(value, "counters.toolCalls", checkpoint.executionCounters().toolCalls());
        put(value, "counters.replans", checkpoint.executionCounters().replans());
        put(value, "counters.steps", checkpoint.executionCounters().steps());
        put(value, "status", checkpoint.status().name());
        put(value, "savedAt", checkpoint.savedAt().toString());
        return value;
    }

    private static AgentCheckpoint fromProperties(Properties value) {
        ArrayList<String> completedSteps = new ArrayList<>();
        for (int index = 0; index < integer(value, "completedStepCount"); index++) {
            completedSteps.add(required(value, "completedStep." + index));
        }
        PendingAction action = new PendingAction(
                required(value, "pending.id"),
                PendingActionType.valueOf(required(value, "pending.type")),
                required(value, "pending.title"), required(value, "pending.description"),
                new LinkedHashMap<>(readMap(value, "pending.payload")));
        return new AgentCheckpoint(
                required(value, "sessionId"), required(value, "invocationId"),
                required(value, "agentId"), required(value, "taskId"),
                required(value, "teamId"), required(value, "userId"),
                required(value, "objective"), required(value, "currentPlanId"),
                required(value, "currentStepId"), integer(value, "currentStepIndex"),
                completedSteps, readMap(value, "state"), action,
                new ExecutionCounters(
                        integer(value, "counters.modelCalls"),
                        integer(value, "counters.toolCalls"),
                        integer(value, "counters.replans"),
                        integer(value, "counters.steps")),
                AgentRunStatus.valueOf(required(value, "status")),
                Instant.parse(required(value, "savedAt")));
    }

    private static void putMap(Properties properties, String prefix, Map<String, String> map) {
        put(properties, prefix + ".count", map.size());
        int index = 0;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            put(properties, prefix + "." + index + ".key", entry.getKey());
            put(properties, prefix + "." + index + ".value", entry.getValue());
            index++;
        }
    }

    private static Map<String, String> readMap(Properties properties, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < integer(properties, prefix + ".count"); index++) {
            result.put(required(properties, prefix + "." + index + ".key"),
                    required(properties, prefix + "." + index + ".value"));
        }
        return result;
    }

    private static void put(Properties properties, String key, Object value) {
        properties.setProperty(key, String.valueOf(value));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("missing checkpoint property: " + key);
        }
        return value;
    }

    private static int integer(Properties properties, String key) {
        return Integer.parseInt(required(properties, key));
    }
}
