package com.github.agentos.server.persistence.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.github.agentos.agent.loop.ContinuationStore;
import com.github.agentos.kernel.AgentCheckpoint;
import com.github.agentos.kernel.AgentEvent;
import com.github.agentos.kernel.AgentEventStore;
import com.github.agentos.kernel.AgentEventType;
import com.github.agentos.kernel.CheckpointStore;
import com.github.agentos.kernel.DefaultAgentEvent;
import com.github.agentos.kernel.EventActions;
import com.github.agentos.kernel.Session;
import com.github.agentos.kernel.SessionService;
import com.github.agentos.kernel.SessionState;
import com.github.agentos.server.security.UserAccount;
import com.github.agentos.server.security.UserStore;
import com.github.agentos.server.settings.SettingsService;
import com.github.agentos.server.usage.UsageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL + MyBatis-Plus 的运行态存储适配器集合。 */
public final class MybatisRuntimeStores {

    private MybatisRuntimeStores() {
    }

    public static final class EventStore implements AgentEventStore {
        private static final Logger LOGGER = LoggerFactory.getLogger(EventStore.class);
        private final AgentEventMapper mapper;
        private final ObjectMapper objectMapper;

        public EventStore(AgentEventMapper mapper, ObjectMapper objectMapper) {
            this.mapper = mapper;
            this.objectMapper = objectMapper;
        }

        @Override
        public void append(AgentEvent event) {
            Objects.requireNonNull(event, "event must not be null");
            try {
                var row = new PersistenceRows.AgentEventRow(
                        event.eventId(), event.sessionId(), event.invocationId(), event.agentId(),
                        event.timestamp(), event.type().name(), event.message(),
                        objectMapper.writeValueAsString(event.data()),
                        objectMapper.writeValueAsString(event.actions()));
                if (mapper.updateById(row) == 0) {
                    mapper.insert(row);
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("[event-store] PostgreSQL append failed type={} eventId={} error={}",
                        event.type(), event.eventId(), exception.getMessage());
            }
        }

        @Override
        public List<AgentEvent> findByInvocationId(String invocationId) {
            return query("invocation_id", requireText(invocationId, "invocationId"));
        }

        @Override
        public List<AgentEvent> findBySessionId(String sessionId) {
            return query("session_id", requireText(sessionId, "sessionId"));
        }

        private List<AgentEvent> query(String column, String value) {
            return mapper.selectList(new QueryWrapper<PersistenceRows.AgentEventRow>()
                            .eq(column, value)
                            .orderByAsc("occurred_at", "event_sequence"))
                    .stream().map(this::toEvent).toList();
        }

        private AgentEvent toEvent(PersistenceRows.AgentEventRow row) {
            try {
                Map<String, Object> data = objectMapper.readValue(
                        row.eventData(), new TypeReference<Map<String, Object>>() { });
                EventActions actions = row.eventActions() == null || row.eventActions().isBlank()
                        ? EventActions.NONE
                        : objectMapper.readValue(row.eventActions(), EventActions.class);
                return new DefaultAgentEvent(
                        row.eventId(), row.sessionId(), row.invocationId(), row.agentId(),
                        row.occurredAt(), AgentEventType.valueOf(row.eventType()), row.message(),
                        data, actions);
            } catch (JacksonException exception) {
                throw decodeFailure("event " + row.eventId(), exception);
            }
        }
    }

    public static final class Checkpoints implements CheckpointStore {
        private final CheckpointMapper mapper;
        private final ObjectMapper objectMapper;

        public Checkpoints(CheckpointMapper mapper, ObjectMapper objectMapper) {
            this.mapper = mapper;
            this.objectMapper = objectMapper;
        }

        @Override
        public void save(AgentCheckpoint checkpoint) {
            Objects.requireNonNull(checkpoint, "checkpoint must not be null");
            try {
                saveRow(mapper, checkpoint.invocationId(), new PersistenceRows.CheckpointRow(
                        checkpoint.invocationId(), objectMapper.writeValueAsString(checkpoint),
                        checkpoint.savedAt()));
            } catch (JacksonException exception) {
                throw encodeFailure("checkpoint " + checkpoint.invocationId(), exception);
            }
        }

        @Override
        public Optional<AgentCheckpoint> load(String invocationId) {
            var row = mapper.selectById(requireText(invocationId, "invocationId"));
            if (row == null) return Optional.empty();
            try {
                return Optional.of(objectMapper.readValue(row.payload(), AgentCheckpoint.class));
            } catch (JacksonException exception) {
                throw decodeFailure("checkpoint " + invocationId, exception);
            }
        }

        @Override
        public void delete(String invocationId) {
            mapper.deleteById(requireText(invocationId, "invocationId"));
        }
    }

    public static final class Continuations implements ContinuationStore {
        private final ContinuationMapper mapper;
        private final ObjectMapper objectMapper;

        public Continuations(ContinuationMapper mapper, ObjectMapper objectMapper) {
            this.mapper = mapper;
            this.objectMapper = objectMapper;
        }

        @Override
        public void save(String invocationId, PersistedContinuation continuation) {
            String id = requireText(invocationId, "invocationId");
            Objects.requireNonNull(continuation, "continuation must not be null");
            try {
                saveRow(mapper, id, new PersistenceRows.ContinuationRow(
                        id, objectMapper.writeValueAsString(continuation), Instant.now()));
            } catch (JacksonException exception) {
                throw encodeFailure("continuation " + id, exception);
            }
        }

        @Override
        public Optional<PersistedContinuation> load(String invocationId) {
            String id = requireText(invocationId, "invocationId");
            var row = mapper.selectById(id);
            if (row == null) return Optional.empty();
            try {
                return Optional.of(objectMapper.readValue(
                        row.payload(), PersistedContinuation.class));
            } catch (JacksonException exception) {
                throw decodeFailure("continuation " + id, exception);
            }
        }

        @Override
        public void delete(String invocationId) {
            mapper.deleteById(requireText(invocationId, "invocationId"));
        }
    }

    public static final class Usages implements UsageStore {
        private static final Logger LOGGER = LoggerFactory.getLogger(Usages.class);
        private final UsageMapper mapper;

        public Usages(UsageMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public void increment(String sessionId, long promptTokens, long completionTokens) {
            if (sessionId == null || sessionId.isBlank()) return;
            try {
                mapper.increment(sessionId.trim(), Math.max(0, promptTokens),
                        Math.max(0, completionTokens));
            } catch (RuntimeException exception) {
                LOGGER.warn("[usage-store] PostgreSQL increment failed sessionId={} error={}",
                        sessionId, exception.getMessage());
            }
        }

        @Override
        public SessionUsage load(String sessionId) {
            var row = mapper.selectById(requireText(sessionId, "sessionId"));
            return row == null ? SessionUsage.zero()
                    : new SessionUsage(row.modelCalls(), row.promptTokens(), row.completionTokens());
        }
    }

    public static class Sessions implements SessionService {
        private final SessionMapper mapper;
        private final ObjectMapper objectMapper;

        public Sessions(SessionMapper mapper, ObjectMapper objectMapper) {
            this.mapper = mapper;
            this.objectMapper = objectMapper;
        }

        @Override
        @Transactional
        public Session getOrCreate(String sessionId, String userId) {
            String id = requireText(sessionId, "sessionId");
            String owner = requireText(userId, "userId");
            PersistenceRows.SessionRow existing = mapper.selectById(id);
            if (existing != null) {
                Session session = toSession(existing);
                if (session.deleted() || !session.userId().equals(owner)) {
                    throw new IllegalArgumentException("session does not belong to current user");
                }
                return session;
            }
            Session created = Session.create(id, owner);
            mapper.insertIfAbsent(toRow(created));
            Session stored = toSession(mapper.selectById(id));
            if (stored.deleted() || !stored.userId().equals(owner)) {
                throw new IllegalArgumentException("session does not belong to current user");
            }
            return stored;
        }

        @Override
        public Optional<Session> find(String sessionId) {
            return Optional.ofNullable(mapper.selectById(requireText(sessionId, "sessionId")))
                    .map(this::toSession)
                    .filter(session -> !session.deleted());
        }

        @Override
        public List<Session> recent(int limit) {
            return recent(0, limit);
        }

        @Override
        public List<Session> recent(int offset, int limit) {
            if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
            if (limit < 1) throw new IllegalArgumentException("limit must be positive");
            return mapper.selectList(new QueryWrapper<PersistenceRows.SessionRow>()
                    .isNull("deleted_at")
                    .orderByDesc("last_active_at")
                    .last("LIMIT " + limit + " OFFSET " + offset))
                    .stream().map(this::toSession).toList();
        }

        @Override
        public long count() {
            return mapper.selectCount(new QueryWrapper<PersistenceRows.SessionRow>()
                    .isNull("deleted_at"));
        }

        @Override
        public List<Session> recentByUser(String userId, int offset, int limit) {
            if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
            if (limit < 1) throw new IllegalArgumentException("limit must be positive");
            return mapper.selectList(new QueryWrapper<PersistenceRows.SessionRow>()
                    .eq("user_id", requireText(userId, "userId"))
                    .isNull("deleted_at")
                    .orderByDesc("last_active_at")
                    .last("LIMIT " + limit + " OFFSET " + offset))
                    .stream().map(this::toSession).toList();
        }

        @Override
        public long countByUser(String userId) {
            return mapper.selectCount(new QueryWrapper<PersistenceRows.SessionRow>()
                    .eq("user_id", requireText(userId, "userId"))
                    .isNull("deleted_at"));
        }

        @Override
        public boolean deleteByUser(String sessionId, String userId) {
            return mapper.update(null, new UpdateWrapper<PersistenceRows.SessionRow>()
                    .set("deleted_at", Instant.now())
                    .eq("session_id", requireText(sessionId, "sessionId"))
                    .eq("user_id", requireText(userId, "userId"))
                    .isNull("deleted_at")) > 0;
        }

        @Override
        public boolean delete(String sessionId) {
            return mapper.update(null, new UpdateWrapper<PersistenceRows.SessionRow>()
                    .set("deleted_at", Instant.now())
                    .eq("session_id", requireText(sessionId, "sessionId"))
                    .isNull("deleted_at")) > 0;
        }

        @Override
        @Transactional
        public Session applyDelta(String sessionId, Map<String, Object> delta) {
            String id = requireText(sessionId, "sessionId");
            PersistenceRows.SessionRow locked = mapper.selectForUpdate(id);
            if (locked == null) {
                Session created = Session.create(id, "unknown-user");
                mapper.insertIfAbsent(toRow(created));
                locked = mapper.selectForUpdate(id);
            }
            Session current = toSession(locked);
            if (current.deleted()) throw new IllegalArgumentException("session has been deleted");
            Session updated = current.withState(current.state().withDelta(delta)).touch(Instant.now());
            mapper.updateById(toRow(updated));
            return updated;
        }

        @Override
        @Transactional
        public Optional<Session> applyDeltaByUser(
                String sessionId, String userId, Map<String, Object> delta) {
            String id = requireText(sessionId, "sessionId");
            String owner = requireText(userId, "userId");
            PersistenceRows.SessionRow locked = mapper.selectForUpdate(id);
            if (locked == null) return Optional.empty();
            Session current = toSession(locked);
            if (current.deleted() || !current.userId().equals(owner)) return Optional.empty();
            Session updated = current.withState(current.state().withDelta(delta)).touch(Instant.now());
            mapper.updateById(toRow(updated));
            return Optional.of(updated);
        }

        private PersistenceRows.SessionRow toRow(Session session) {
            try {
                return new PersistenceRows.SessionRow(
                        session.sessionId(), session.userId(),
                        objectMapper.writeValueAsString(session.state().asMap()),
                        session.createdAt(), session.lastActiveAt(), session.deletedAt());
            } catch (JacksonException exception) {
                throw encodeFailure("session " + session.sessionId(), exception);
            }
        }

        private Session toSession(PersistenceRows.SessionRow row) {
            try {
                Map<String, Object> state = objectMapper.readValue(
                        row.statePayload(), new TypeReference<Map<String, Object>>() { });
                return new Session(row.sessionId(), row.userId(), row.createdAt(),
                        row.lastActiveAt(), SessionState.of(state), row.deletedAt());
            } catch (JacksonException exception) {
                throw decodeFailure("session " + row.sessionId(), exception);
            }
        }
    }

    public static final class Users implements UserStore {
        private final UserMapper mapper;

        public Users(UserMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Optional<UserAccount> findByUsername(String username) {
            return Optional.ofNullable(mapper.selectById(normalizeUsername(username)))
                    .map(Users::toAccount);
        }

        @Override
        public List<UserAccount> list() {
            return mapper.selectList(new QueryWrapper<PersistenceRows.UserRow>()
                            .orderByAsc("created_at", "username"))
                    .stream().map(Users::toAccount).toList();
        }

        @Override
        public void create(UserAccount account) {
            Objects.requireNonNull(account, "account must not be null");
            try {
                mapper.insert(toRow(account));
            } catch (DuplicateKeyException exception) {
                throw new IllegalStateException("user already exists: " + account.username(), exception);
            }
        }

        @Override
        public boolean updatePasswordHash(String username, String passwordHash) {
            return mapper.update(null, new UpdateWrapper<PersistenceRows.UserRow>()
                    .eq("username", normalizeUsername(username))
                    .set("password_hash", Objects.requireNonNull(passwordHash))) > 0;
        }

        @Override
        public boolean updateRoles(String username, Set<String> roles) {
            UserAccount normalized = new UserAccount(
                    normalizeUsername(username), "unused-password-hash", roles, Instant.EPOCH);
            return mapper.update(null, new UpdateWrapper<PersistenceRows.UserRow>()
                    .eq("username", normalized.username())
                    .set("roles", String.join(",", normalized.roles()))) > 0;
        }

        @Override
        public boolean delete(String username) {
            return mapper.deleteById(normalizeUsername(username)) > 0;
        }

        @Override
        public long count() {
            return mapper.selectCount(null);
        }

        private static PersistenceRows.UserRow toRow(UserAccount account) {
            return new PersistenceRows.UserRow(account.username(), account.passwordHash(),
                    String.join(",", account.roles()), account.createdAt());
        }

        private static UserAccount toAccount(PersistenceRows.UserRow row) {
            return new UserAccount(row.username(), row.passwordHash(),
                    row.roles() == null || row.roles().isBlank()
                            ? Set.of() : Set.of(row.roles().split(",")), row.createdAt());
        }
    }

    private static <T> void saveRow(
            com.baomidou.mybatisplus.core.mapper.BaseMapper<T> mapper,
            java.io.Serializable id,
            T row) {
        if (mapper.selectById(id) == null) {
            try {
                mapper.insert(row);
                return;
            } catch (DuplicateKeyException ignored) {
                // Concurrent writer inserted the same logical row; update it below.
            }
        }
        mapper.updateById(row);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private static IllegalStateException encodeFailure(String target, Exception exception) {
        return new IllegalStateException(
                "PostgreSQL persistence failed while encoding " + target + ": "
                        + exception.getMessage(), exception);
    }

    private static IllegalStateException decodeFailure(String target, Exception exception) {
        return new IllegalStateException(
                "PostgreSQL persistence failed while decoding " + target + ": "
                        + exception.getMessage(), exception);
    }

    /** settings 表读写。 */
    public static final class Settings implements SettingsService {
        private final SettingsMapper mapper;

        public Settings(SettingsMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Optional<String> read(String key) {
            String id = requireText(key, "settingKey");
            PersistenceRows.SettingRow row = mapper.selectById(id);
            return row == null ? Optional.empty() : Optional.of(row.settingValue());
        }

        @Override
        public void write(String key, String value, String updatedBy) {
            String id = requireText(key, "settingKey");
            String text = value == null ? "" : value;
            String by = updatedBy == null ? "" : updatedBy;
            try {
                mapper.insert(new PersistenceRows.SettingRow(id, text, Instant.now(), by));
            } catch (DuplicateKeyException exception) {
                mapper.updateById(new PersistenceRows.SettingRow(id, text, Instant.now(), by));
            }
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value.trim();
        }
    }
}
