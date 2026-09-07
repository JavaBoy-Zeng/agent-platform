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
        Session session = sessions.computeIfAbsent(
                sessionId, ignored -> Session.create(sessionId, userId));
        if (session.deleted() || !session.userId().equals(userId)) {
            throw new IllegalArgumentException("session does not belong to current user");
        }
        return session;
    }

    @Override
    public Optional<Session> find(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId)).filter(session -> !session.deleted());
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
                .filter(session -> !session.deleted())
                .sorted(Comparator.comparing(Session::lastActiveAt).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long count() {
        return sessions.values().stream().filter(session -> !session.deleted()).count();
    }

    @Override
    public List<Session> recentByUser(String userId, int offset, int limit) {
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return sessions.values().stream()
                .filter(session -> !session.deleted() && session.userId().equals(userId))
                .sorted(Comparator.comparing(Session::lastActiveAt).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countByUser(String userId) {
        return sessions.values().stream()
                .filter(session -> !session.deleted() && session.userId().equals(userId))
                .count();
    }

    @Override
    public boolean deleteByUser(String sessionId, String userId) {
        java.util.concurrent.atomic.AtomicBoolean deleted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.deleted() || !session.userId().equals(userId)) return session;
            deleted.set(true);
            return session.softDelete(Instant.now());
        });
        return deleted.get();
    }

    @Override
    public boolean delete(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        java.util.concurrent.atomic.AtomicBoolean deleted = new java.util.concurrent.atomic.AtomicBoolean(false);
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.deleted()) return session;
            deleted.set(true);
            return session.softDelete(Instant.now());
        });
        return deleted.get();
    }

    @Override
    public Session applyDelta(String sessionId, Map<String, Object> delta) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        return sessions.compute(sessionId, (key, existing) -> {
            Session session = existing == null
                    ? Session.create(sessionId, "unknown-user") : existing;
            if (session.deleted()) {
                throw new IllegalArgumentException("session has been deleted");
            }
            return session
                    .withState(session.state().withDelta(delta))
                    .touch(Instant.now());
        });
    }

    @Override
    public Optional<Session> applyDeltaByUser(
            String sessionId, String userId, Map<String, Object> delta) {
        java.util.concurrent.atomic.AtomicReference<Session> updated =
                new java.util.concurrent.atomic.AtomicReference<>();
        sessions.computeIfPresent(sessionId, (id, session) -> {
            if (session.deleted() || !session.userId().equals(userId)) return session;
            Session next = session.withState(session.state().withDelta(delta)).touch(Instant.now());
            updated.set(next);
            return next;
        });
        return Optional.ofNullable(updated.get());
    }
}
