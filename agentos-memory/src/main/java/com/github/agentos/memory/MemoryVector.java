package com.github.agentos.memory;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/** 与某个原子记忆内容版本绑定的持久化向量。 */
public final class MemoryVector {

    private final String memoryId;
    private final String model;
    private final int contentVersion;
    private final double[] values;
    private final Instant updatedAt;

    public MemoryVector(
            String memoryId,
            String model,
            int contentVersion,
            double[] values,
            Instant updatedAt) {
        if (memoryId == null || memoryId.isBlank()) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (contentVersion < 1) throw new IllegalArgumentException("contentVersion must be positive");
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("values must not be empty");
        }
        double[] copy = values.clone();
        for (double value : copy) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("vector values must be finite");
        }
        this.memoryId = memoryId.trim();
        this.model = model.trim();
        this.contentVersion = contentVersion;
        this.values = copy;
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public String memoryId() {
        return memoryId;
    }

    public String model() {
        return model;
    }

    public int contentVersion() {
        return contentVersion;
    }

    public double[] values() {
        return values.clone();
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MemoryVector that)) return false;
        return contentVersion == that.contentVersion
                && memoryId.equals(that.memoryId)
                && model.equals(that.model)
                && updatedAt.equals(that.updatedAt)
                && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(memoryId, model, contentVersion, updatedAt);
        return 31 * result + Arrays.hashCode(values);
    }
}
