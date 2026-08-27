package com.github.agentos.server.persistence;

import com.github.agentos.server.security.UserAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite 用户存储测试。 */
class SqliteUserStoreTest {

    @TempDir
    Path tempDir;

    private SqliteUserStore store;

    @BeforeEach
    void setUp() {
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("users-test.sqlite"));
        store = new SqliteUserStore(dataSource);
    }

    @Test
    void createFindAndUpdateAndDelete() {
        store.create(new UserAccount("whale", "hash-1", Set.of("ADMIN"), null));
        store.create(new UserAccount("alice", "hash-2", Set.of("USER"), null));

        assertEquals(2, store.count());
        assertTrue(store.findByUsername("whale").isPresent());
        assertEquals("hash-1", store.findByUsername("whale").orElseThrow().passwordHash());
        assertEquals(Set.of("ADMIN"), store.findByUsername("whale").orElseThrow().roles());
        assertTrue(store.findByUsername("missing").isEmpty());

        assertTrue(store.updatePasswordHash("whale", "hash-1b"));
        assertEquals("hash-1b", store.findByUsername("whale").orElseThrow().passwordHash());
        assertFalse(store.updatePasswordHash("missing", "x"));

        assertTrue(store.delete("alice"));
        assertFalse(store.delete("alice"));
        assertEquals(1, store.count());
        // roles 按逗号往返
        assertEquals(Set.of("ADMIN"), store.list().get(0).roles());
    }

    @Test
    void duplicateUsernameRejected() {
        store.create(new UserAccount("dup", "h", Set.of(), null));
        assertThrows(IllegalStateException.class,
                () -> store.create(new UserAccount("dup", "h2", Set.of(), null)));
    }

    @Test
    void schemaSurvivesReopen() {
        store.create(new UserAccount("persist", "hash", Set.of("USER"), null));
        org.sqlite.SQLiteDataSource dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("users-test.sqlite"));
        SqliteUserStore reopened = new SqliteUserStore(dataSource);
        assertTrue(reopened.findByUsername("persist").isPresent());
    }
}
