package com.github.agentos.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** L1 中可独立检索、去重和演进的一条原子记忆。 */
public record AtomicMemory(
        String id,
        MemoryScope scope,
        MemoryType type,
        String content,
        double confidence,
        int priority,
        int version,
        String sourceTurnId,
        List<String> sourceTurnIds,
        MemoryStatus status,
        Instant validFrom,
        Instant expiresAt,
        String supersededById,
        Instant createdAt,
        Instant updatedAt) {

    public AtomicMemory {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        scope = Objects.requireNonNull(scope, "scope must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        if (content == null || content.isBlank()) throw new IllegalArgumentException("content must not be blank");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (priority < 0 || priority > 10) throw new IllegalArgumentException("priority must be between 0 and 10");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        sourceTurnId = requireText(sourceTurnId, "sourceTurnId");
        sourceTurnIds = normalizeSources(sourceTurnIds, sourceTurnId);
        status = Objects.requireNonNull(status, "status must not be null");
        validFrom = Objects.requireNonNull(validFrom, "validFrom must not be null");
        if (expiresAt != null && !expiresAt.isAfter(validFrom)) {
            throw new IllegalArgumentException("expiresAt must be after validFrom");
        }
        supersededById = Objects.requireNonNullElse(supersededById, "").trim();
        if (status == MemoryStatus.SUPERSEDED && supersededById.isEmpty()) {
            throw new IllegalArgumentException("superseded memory must reference its replacement");
        }
        createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    /** 兼容旧的持久化模型和调用方。 */
    public AtomicMemory(
            String id,
            MemoryScope scope,
            MemoryType type,
            String content,
            double confidence,
            int priority,
            int version,
            String sourceTurnId,
            Instant createdAt,
            Instant updatedAt) {
        this(id, scope, type, content, confidence, priority, version, sourceTurnId,
                List.of(sourceTurnId), MemoryStatus.ACTIVE, createdAt, null, "", createdAt, updatedAt);
    }

    public static AtomicMemory create(
            MemoryScope scope,
            MemoryType type,
            String content,
            double confidence,
            int priority,
            String sourceTurnId) {
        return create(scope, type, content, confidence, priority, sourceTurnId, null);
    }

    /** 创建带可选到期时间的初始原子记忆。 */
    public static AtomicMemory create(
            MemoryScope scope,
            MemoryType type,
            String content,
            double confidence,
            int priority,
            String sourceTurnId,
            Instant expiresAt) {
        Instant now = Instant.now();
        return new AtomicMemory(
                UUID.randomUUID().toString(), scope, type, content.trim(), confidence,
                priority, 1, sourceTurnId, List.of(sourceTurnId), MemoryStatus.ACTIVE,
                now, expiresAt, "", now, now);
    }

    /** 合并新的表达并递增版本。 */
    public AtomicMemory revise(String revisedContent, double revisedConfidence, int revisedPriority, String turnId) {
        List<String> sources = new ArrayList<>(sourceTurnIds);
        if (!sources.contains(turnId)) sources.add(turnId);
        return new AtomicMemory(
                id, scope, type, revisedContent.trim(), revisedConfidence, revisedPriority,
                version + 1, turnId, sources, MemoryStatus.ACTIVE, validFrom, expiresAt, "",
                createdAt, Instant.now());
    }

    /** 人工纠正正文及有效期，并保留原始来源链。 */
    public AtomicMemory correct(String revisedContent, Instant revisedExpiresAt, String correctionSource) {
        String source = requireText(correctionSource, "correctionSource");
        List<String> sources = appendSource(source);
        return new AtomicMemory(
                id, scope, type, revisedContent.trim(), 1.0, Math.max(priority, 9),
                version + 1, source, sources, MemoryStatus.ACTIVE, validFrom,
                revisedExpiresAt, "", createdAt, Instant.now());
    }

    /** 返回带新失效时间的版本。 */
    public AtomicMemory withExpiration(Instant revisedExpiresAt) {
        return new AtomicMemory(
                id, scope, type, content, confidence, priority, version + 1, sourceTurnId,
                sourceTurnIds, status, validFrom, revisedExpiresAt, supersededById,
                createdAt, Instant.now());
    }

    /** 将记忆软失效，保留审计和来源信息。 */
    public AtomicMemory invalidate(String source) {
        List<String> sources = appendSource(source);
        return new AtomicMemory(
                id, scope, type, content, confidence, priority, version + 1, source,
                sources, MemoryStatus.INVALIDATED, validFrom, expiresAt, "", createdAt, Instant.now());
    }

    /** 将旧记忆标记为已被 replacementId 替代。 */
    public AtomicMemory supersedeBy(String replacementId, String source) {
        String replacement = requireText(replacementId, "replacementId");
        List<String> sources = appendSource(source);
        return new AtomicMemory(
                id, scope, type, content, confidence, priority, version + 1, source,
                sources, MemoryStatus.SUPERSEDED, validFrom, expiresAt, replacement,
                createdAt, Instant.now());
    }

    /** 指定时刻是否可参与正常召回。 */
    public boolean activeAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return status == MemoryStatus.ACTIVE
                && !validFrom.isAfter(instant)
                && (expiresAt == null || expiresAt.isAfter(instant));
    }

    private List<String> appendSource(String source) {
        String value = requireText(source, "source");
        List<String> sources = new ArrayList<>(sourceTurnIds);
        if (!sources.contains(value)) sources.add(value);
        return List.copyOf(sources);
    }

    private static List<String> normalizeSources(List<String> values, String latest) {
        List<String> result = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank() && !result.contains(value.trim())) {
                    result.add(value.trim());
                }
            }
        }
        if (!result.contains(latest)) result.add(latest);
        return List.copyOf(result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
