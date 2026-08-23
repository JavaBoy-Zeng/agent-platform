package com.github.agentos.memory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 使用显式二进制格式和原子替换提供进程重启可恢复能力的本地存储。
 *
 * <p>该实现不依赖 Java 原生序列化，文件格式带版本号，后续可以迁移到 JDBC/Lucene。</p>
 */
public final class FileMemoryStore extends InMemoryMemoryStore {

    private static final int MAGIC = 0x41474D31; // AGM1
    private static final int FORMAT_VERSION = 2;
    private final Path stateFile;
    private boolean loading;

    public FileMemoryStore(Path directory) {
        try {
            Path normalized = directory.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            this.stateFile = normalized.resolve("memory-state.bin");
            loading = true;
            load();
            loading = false;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to initialize memory store", exception);
        }
    }

    public Path stateFile() {
        return stateFile;
    }

    @Override
    protected synchronized void onMutation() {
        if (loading) return;
        persist();
    }

    private void load() throws IOException {
        if (!Files.exists(stateFile)) return;
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(stateFile)))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid memory state magic");
            int version = input.readInt();
            if (version < 1 || version > FORMAT_VERSION) {
                throw new IOException("unsupported memory state version: " + version);
            }

            readItems(input, () -> {
                CompletedTurn turn = readTurn(input, version);
                turns.put(turn.id(), turn);
            });
            readItems(input, () -> {
                AtomicMemory memory = readAtomic(input, version);
                atomicMemories.put(memory.id(), memory);
            });
            readItems(input, () -> {
                ScenarioMemory scenario = readScenario(input);
                scenarios.put(scenario.id(), scenario);
            });
            readItems(input, () -> {
                ProfileMemory profile = readProfile(input);
                profiles.put(actorKey(profile.scope()), profile);
            });
            readItems(input, () -> {
                PipelineJob job = readJob(input);
                jobs.put(job.id(), job);
            });
            if (version >= 2) {
                readItems(input, () -> {
                    MemoryVector vector = readVector(input);
                    vectors.put(vector.memoryId() + '\u0000' + vector.model(), vector);
                });
            }
        } catch (EOFException exception) {
            throw new IOException("truncated memory state file", exception);
        }
    }

    private synchronized void persist() {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(stateFile.getParent(), "memory-state-", ".tmp");
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);

                output.writeInt(turns.size());
                for (CompletedTurn turn : turns.values()) writeTurn(output, turn);
                output.writeInt(atomicMemories.size());
                for (AtomicMemory memory : atomicMemories.values()) writeAtomic(output, memory);
                output.writeInt(scenarios.size());
                for (ScenarioMemory scenario : scenarios.values()) writeScenario(output, scenario);
                output.writeInt(profiles.size());
                for (ProfileMemory profile : profiles.values()) writeProfile(output, profile);
                output.writeInt(jobs.size());
                for (PipelineJob job : jobs.values()) writeJob(output, job);
                output.writeInt(vectors.size());
                for (MemoryVector vector : vectors.values()) writeVector(output, vector);
            }
            try {
                Files.move(temporary, stateFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to persist memory state", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    private static void writeTurn(DataOutputStream out, CompletedTurn turn) throws IOException {
        writeString(out, turn.id());
        writeString(out, turn.businessKey());
        writeScope(out, turn.scope());
        writeString(out, turn.userInput());
        writeString(out, turn.assistantOutput());
        out.writeInt(turn.toolOutputs().size());
        for (String item : turn.toolOutputs()) writeString(out, item);
        out.writeLong(turn.completedAt().toEpochMilli());
    }

    private static CompletedTurn readTurn(DataInputStream in, int formatVersion) throws IOException {
        String id = readString(in);
        String businessKey = formatVersion >= 2 ? readString(in) : id;
        MemoryScope scope = readScope(in);
        String userInput = readString(in);
        String assistantOutput = readString(in);
        int count = checkedCount(in.readInt());
        List<String> toolOutputs = new ArrayList<>(count);
        for (int index = 0; index < count; index++) toolOutputs.add(readString(in));
        return new CompletedTurn(
                id, businessKey, scope, userInput, assistantOutput, toolOutputs,
                Instant.ofEpochMilli(in.readLong()));
    }

    private static void writeAtomic(DataOutputStream out, AtomicMemory memory) throws IOException {
        writeString(out, memory.id());
        writeScope(out, memory.scope());
        writeString(out, memory.type().name());
        writeString(out, memory.content());
        out.writeDouble(memory.confidence());
        out.writeInt(memory.priority());
        out.writeInt(memory.version());
        writeString(out, memory.sourceTurnId());
        out.writeInt(memory.sourceTurnIds().size());
        for (String source : memory.sourceTurnIds()) writeString(out, source);
        writeString(out, memory.status().name());
        out.writeLong(memory.validFrom().toEpochMilli());
        writeNullableInstant(out, memory.expiresAt());
        writeString(out, memory.supersededById());
        out.writeLong(memory.createdAt().toEpochMilli());
        out.writeLong(memory.updatedAt().toEpochMilli());
    }

    private static AtomicMemory readAtomic(DataInputStream in, int formatVersion) throws IOException {
        String id = readString(in);
        MemoryScope scope = readScope(in);
        MemoryType type = MemoryType.valueOf(readString(in));
        String content = readString(in);
        double confidence = in.readDouble();
        int priority = in.readInt();
        int memoryVersion = in.readInt();
        String latestSource = readString(in);
        if (formatVersion == 1) {
            Instant createdAt = Instant.ofEpochMilli(in.readLong());
            Instant updatedAt = Instant.ofEpochMilli(in.readLong());
            return new AtomicMemory(
                    id, scope, type, content, confidence, priority, memoryVersion,
                    latestSource, createdAt, updatedAt);
        }
        int sourceCount = checkedCount(in.readInt());
        List<String> sources = new ArrayList<>(sourceCount);
        for (int index = 0; index < sourceCount; index++) sources.add(readString(in));
        MemoryStatus status = MemoryStatus.valueOf(readString(in));
        Instant validFrom = Instant.ofEpochMilli(in.readLong());
        Instant expiresAt = readNullableInstant(in);
        String supersededById = readString(in);
        Instant createdAt = Instant.ofEpochMilli(in.readLong());
        Instant updatedAt = Instant.ofEpochMilli(in.readLong());
        return new AtomicMemory(
                id, scope, type, content, confidence, priority, memoryVersion, latestSource,
                sources, status, validFrom, expiresAt, supersededById, createdAt, updatedAt);
    }

    private static void writeVector(DataOutputStream out, MemoryVector vector) throws IOException {
        writeString(out, vector.memoryId());
        writeString(out, vector.model());
        out.writeInt(vector.contentVersion());
        double[] values = vector.values();
        out.writeInt(values.length);
        for (double value : values) out.writeDouble(value);
        out.writeLong(vector.updatedAt().toEpochMilli());
    }

    private static MemoryVector readVector(DataInputStream in) throws IOException {
        String memoryId = readString(in);
        String model = readString(in);
        int contentVersion = in.readInt();
        int dimension = checkedCount(in.readInt());
        if (dimension == 0) throw new IOException("vector dimension must be positive");
        double[] values = new double[dimension];
        for (int index = 0; index < dimension; index++) values[index] = in.readDouble();
        return new MemoryVector(
                memoryId, model, contentVersion, values, Instant.ofEpochMilli(in.readLong()));
    }

    private static void writeNullableInstant(DataOutputStream out, Instant value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeLong(value.toEpochMilli());
    }

    private static Instant readNullableInstant(DataInputStream in) throws IOException {
        return in.readBoolean() ? Instant.ofEpochMilli(in.readLong()) : null;
    }

    private static void writeScenario(DataOutputStream out, ScenarioMemory scenario) throws IOException {
        writeString(out, scenario.id());
        writeScope(out, scenario.scope());
        writeString(out, scenario.name());
        writeString(out, scenario.content());
        out.writeInt(scenario.version());
        out.writeLong(scenario.updatedAt().toEpochMilli());
    }

    private static ScenarioMemory readScenario(DataInputStream in) throws IOException {
        return new ScenarioMemory(
                readString(in), readScope(in), readString(in), readString(in), in.readInt(),
                Instant.ofEpochMilli(in.readLong()));
    }

    private static void writeProfile(DataOutputStream out, ProfileMemory profile) throws IOException {
        writeString(out, profile.id());
        writeScope(out, profile.scope());
        writeString(out, profile.content());
        out.writeInt(profile.version());
        out.writeLong(profile.updatedAt().toEpochMilli());
    }

    private static ProfileMemory readProfile(DataInputStream in) throws IOException {
        return new ProfileMemory(
                readString(in), readScope(in), readString(in), in.readInt(),
                Instant.ofEpochMilli(in.readLong()));
    }

    private static void writeJob(DataOutputStream out, PipelineJob job) throws IOException {
        writeString(out, job.id());
        writeString(out, job.turnId());
        writeScope(out, job.scope());
        writeString(out, job.stage().name());
        writeString(out, job.status().name());
        out.writeInt(job.attempts());
        writeString(out, job.error());
        out.writeLong(job.updatedAt().toEpochMilli());
    }

    private static PipelineJob readJob(DataInputStream in) throws IOException {
        return new PipelineJob(
                readString(in), readString(in), readScope(in),
                PipelineJob.Stage.valueOf(readString(in)), PipelineJob.Status.valueOf(readString(in)),
                in.readInt(), readString(in), Instant.ofEpochMilli(in.readLong()));
    }

    private static void writeScope(DataOutputStream out, MemoryScope scope) throws IOException {
        writeString(out, scope.teamId());
        writeString(out, scope.userId());
        writeString(out, scope.agentId());
        writeString(out, scope.sessionId());
        writeString(out, scope.taskId());
    }

    private static MemoryScope readScope(DataInputStream in) throws IOException {
        return new MemoryScope(readString(in), readString(in), readString(in), readString(in), readString(in));
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("invalid string length: " + length);
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("unexpected end of memory state");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int checkedCount(int count) throws IOException {
        if (count < 0 || count > 1_000_000) throw new IOException("invalid item count: " + count);
        return count;
    }

    private static void readItems(DataInputStream input, IoAction action) throws IOException {
        int count = checkedCount(input.readInt());
        for (int index = 0; index < count; index++) action.run();
    }

    private static String actorKey(MemoryScope scope) {
        return scope.teamId() + '\u0000' + scope.userId() + '\u0000' + scope.agentId();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
