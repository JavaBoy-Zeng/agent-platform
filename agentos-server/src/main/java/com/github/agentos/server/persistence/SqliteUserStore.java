package com.github.agentos.server.persistence;

import com.github.agentos.server.security.UserAccount;
import com.github.agentos.server.security.UserStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 基于 SQLite 的用户存储；users 表由 SqliteSupport 统一迁移。 */
public final class SqliteUserStore implements UserStore {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(SqliteUserStore.class);

    private final DataSource dataSource;

    /** 创建 SQLite 用户存储。 */
    public SqliteUserStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        SqliteSupport.initializeSchema(dataSource);
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT username, password_hash, roles, created_at_ms "
                                + "FROM users WHERE username = ?")) {
            statement.setString(1, normalize(username));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw SqliteSupport.failure("finding user " + username, exception);
        }
    }

    @Override
    public List<UserAccount> list() {
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT username, password_hash, roles, created_at_ms "
                                + "FROM users ORDER BY created_at_ms, username")) {
            try (ResultSet result = statement.executeQuery()) {
                List<UserAccount> accounts = new java.util.ArrayList<>();
                while (result.next()) {
                    accounts.add(read(result));
                }
                return List.copyOf(accounts);
            }
        } catch (SQLException exception) {
            throw SqliteSupport.failure("listing users", exception);
        }
    }

    @Override
    public void create(UserAccount account) {
        Objects.requireNonNull(account, "account must not be null");
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO users (username, password_hash, roles, created_at_ms) "
                                + "VALUES (?, ?, ?, ?)")) {
            statement.setString(1, account.username());
            statement.setString(2, account.passwordHash());
            statement.setString(3, String.join(",", account.roles()));
            statement.setTimestamp(4, Timestamp.from(account.createdAt()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().contains("UNIQUE")) {
                throw new IllegalStateException(
                        "user already exists: " + account.username(), exception);
            }
            throw SqliteSupport.failure("creating user " + account.username(), exception);
        }
    }

    @Override
    public boolean updatePasswordHash(String username, String passwordHash) {
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET password_hash = ? WHERE username = ?")) {
            statement.setString(1, passwordHash);
            statement.setString(2, normalize(username));
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw SqliteSupport.failure("updating password " + username, exception);
        }
    }

    @Override
    public boolean updateRoles(String username, Set<String> roles) {
        UserAccount normalized = new UserAccount(
                normalize(username), "unused-password-hash", roles, Instant.EPOCH);
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE users SET roles = ? WHERE username = ?")) {
            statement.setString(1, String.join(",", normalized.roles()));
            statement.setString(2, normalized.username());
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw SqliteSupport.failure("updating roles " + username, exception);
        }
    }

    @Override
    public boolean delete(String username) {
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM users WHERE username = ?")) {
            statement.setString(1, normalize(username));
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            throw SqliteSupport.failure("deleting user " + username, exception);
        }
    }

    @Override
    public long count() {
        try (Connection connection = SqliteSupport.open(dataSource);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM users")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        } catch (SQLException exception) {
            throw SqliteSupport.failure("counting users", exception);
        }
    }

    private static UserAccount read(ResultSet result) throws SQLException {
        String roles = result.getString(3);
        long createdAtMs = result.getTimestamp(4).getTime();
        LOGGER.debug("[user-store] loaded {}", result.getString(1));
        return new UserAccount(
                result.getString(1),
                result.getString(2),
                roles == null || roles.isBlank()
                        ? Set.of()
                        : Set.of(roles.split(",")),
                Instant.ofEpochMilli(createdAtMs));
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim();
    }
}
