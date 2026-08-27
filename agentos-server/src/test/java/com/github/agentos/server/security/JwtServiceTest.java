package com.github.agentos.server.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手写 HS256 JWT 服务测试。 */
class JwtServiceTest {

    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef".getBytes();

    @Test
    void issueAndParseRoundTrip() {
        JwtService service = new JwtService(SECRET, Duration.ofDays(30));
        String token = service.issue("whale", Set.of("ADMIN", "MEMORY_ADMIN"));

        JwtService.AuthenticatedUser user = service.parse(token);
        assertEquals("whale", user.username());
        assertEquals(Set.of("ADMIN", "MEMORY_ADMIN"), user.roles());
        assertTrue(user.expiresAt().isAfter(java.time.Instant.now().plus(Duration.ofDays(29))));
    }

    @Test
    void tamperedPayloadIsRejected() {
        JwtService service = new JwtService(SECRET, Duration.ofDays(30));
        String token = service.issue("alice", Set.of("USER"));
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." +
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "{\"sub\":\"admin\",\"roles\":\"ADMIN\",\"iat\":0,\"exp\":9999999999,\"iss\":\"agentos\"}"
                                .getBytes()) + "." + parts[2];
        assertThrows(JwtService.InvalidTokenException.class, () -> service.parse(tampered));
    }

    @Test
    void wrongSecretIsRejected() {
        JwtService signer = new JwtService(SECRET, Duration.ofDays(30));
        JwtService verifier = new JwtService(
                "ffff456789abcdef0123456789abcdef0123456789abcdef".getBytes(), Duration.ofDays(30));
        String token = signer.issue("alice", Set.of());
        assertThrows(JwtService.InvalidTokenException.class, () -> verifier.parse(token));
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtService service = new JwtService(SECRET, Duration.ofMillis(300));
        String token = service.issue("alice", Set.of());
        Thread.sleep(700);
        JwtService.InvalidTokenException exception = assertThrows(
                JwtService.InvalidTokenException.class, () -> service.parse(token));
        assertTrue(exception.getMessage().contains("expired"));
    }

    @Test
    void rejectsWeakSecretAndBlankToken() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("short".getBytes(), Duration.ofDays(1)));
        JwtService service = new JwtService(SECRET, Duration.ofDays(1));
        assertThrows(JwtService.InvalidTokenException.class, () -> service.parse(""));
        assertThrows(JwtService.InvalidTokenException.class, () -> service.parse(null));
    }
}
