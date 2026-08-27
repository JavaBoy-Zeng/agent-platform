package com.github.agentos.server.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PBKDF2 密码哈希测试。 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashAndVerifyRoundTrip() {
        String hash = hasher.hash("s3cret-密码");
        assertTrue(hash.startsWith("pbkdf2:"));
        assertTrue(hasher.verify("s3cret-密码", hash));
        assertFalse(hasher.verify("wrong", hash));
        assertFalse(hasher.verify(null, hash));
    }

    @Test
    void samePasswordProducesDistinctHashes() {
        String first = hasher.hash("same");
        String second = hasher.hash("same");
        assertNotEquals(first, second);
        assertTrue(hasher.verify("same", first));
        assertTrue(hasher.verify("same", second));
    }

    @Test
    void malformedStoredHashFailsClosed() {
        assertFalse(hasher.verify("x", ""));
        assertFalse(hasher.verify("x", "plaintext"));
        assertFalse(hasher.verify("x", "pbkdf2:abc:###:###"));
        assertFalse(hasher.verify("x", null));
    }

    @Test
    void legacyIterationsStillVerifiable() {
        PasswordHasher legacy = new PasswordHasher(1_000);
        String hash = legacy.hash("old");
        assertTrue(hash.startsWith("pbkdf2:1000:"));
        // 默认迭代数的 hasher 仍可校验旧哈希（按存储串内迭代数重算）。
        assertTrue(hasher.verify("old", hash));
    }

    @Test
    void rejectsWeakIterations() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(10));
        assertDoesNotThrow(() -> new PasswordHasher(1_000));
    }
}
