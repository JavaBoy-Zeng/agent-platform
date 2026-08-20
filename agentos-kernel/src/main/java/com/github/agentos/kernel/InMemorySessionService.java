package com.github.agentos.kernel;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内会话服务实现；同一会话的读改写通过 Map.compute 串行化。 */
public final class InMemorySessionService implements SessionService {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public Session getOrCreate(String sessionId, String userId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        return sessions.computeIfAbsent(sessionId, ignored -> Session.create(sessionId, userId));
    }

    @Override
    public Optional<Session> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<Session> recent(int limit) {
        return recent(0, limit);
    }

    @Override
    public List<Session> recent(int offset, int limit) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return sessions.values().stream()
                .sorted(Comparator.comparing(Session::lastActiveAt).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long count() {
        return sessions.size();
    }

    @Override
    public boolean delete(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return sessions.remove(sessionId) != null;
    }

    @Override
    public Session applyDelta(String sessionId, Map<String, Object> delta) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return sessions.compute(sessionId, (key, existing) -> {
            Session session = existing == null
                    ? Session.create(sessionId, "unknown-user") : existing;
            return session
                    .withState(session.state().withDelta(delta))
                    .touch(Instant.now());
        });
    }
}
