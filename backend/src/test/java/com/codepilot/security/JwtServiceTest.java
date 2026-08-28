package com.codepilot.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService("test-secret-value-not-for-production", 60_000L);

    @Test
    void generateToken_roundTripsUserIdAndEmail() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "alice@example.com");

        assertThat(token).isNotBlank();
        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractUserId(token)).isEqualTo(userId);
        assertThat(jwtService.parseClaims(token).get("email", String.class)).isEqualTo("alice@example.com");
    }

    @Test
    void isValid_returnsFalseForGarbageToken() {
        assertThat(jwtService.isValid("not.a.jwt")).isFalse();
    }

    @Test
    void parseClaims_throwsForTamperedToken() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateToken(userId, "bob@example.com");
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> jwtService.parseClaims(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredToken_isRejected() throws InterruptedException {
        JwtService shortLived = new JwtService("test-secret-value-not-for-production", 1L);
        String token = shortLived.generateToken(UUID.randomUUID(), "carol@example.com");

        Thread.sleep(20);

        assertThat(shortLived.isValid(token)).isFalse();
    }
}
