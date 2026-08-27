package com.github.agentos.server.security;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内用户存储；用于 memory 持久化模式，重启后需重新引导。 */
public final class InMemoryUserStore implements UserStore {

    private final Map<String, UserAccount> users = new ConcurrentHashMap<>();

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(users.get(username.trim()));
    }

    @Override
    public List<UserAccount> list() {
        return users.values().stream()
                .sorted(Comparator.comparing(UserAccount::createdAt)
                        .thenComparing(UserAccount::username))
                .toList();
    }

    @Override
    public void create(UserAccount account) {
        Objects.requireNonNull(account, "account must not be null");
        if (users.putIfAbsent(account.username(), account) != null) {
            throw new IllegalStateException("user already exists: " + account.username());
        }
    }

    @Override
    public boolean updatePasswordHash(String username, String passwordHash) {
        UserAccount current = users.get(normalize(username));
        if (current == null) {
            return false;
        }
        users.replace(current.username(),
                new UserAccount(current.username(), passwordHash, current.roles(),
                        current.createdAt()));
        return true;
    }

    @Override
    public boolean updateRoles(String username, Set<String> roles) {
        UserAccount current = users.get(normalize(username));
        if (current == null) {
            return false;
        }
        users.replace(current.username(),
                new UserAccount(current.username(), current.passwordHash(), roles,
                        current.createdAt()));
        return true;
    }

    @Override
    public boolean delete(String username) {
        return users.remove(normalize(username)) != null;
    }

    @Override
    public long count() {
        return users.size();
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim();
    }
}
