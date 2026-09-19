package com.tiktok.security.jwt;

import com.tiktok.crypto.jwt.JwtProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisRevokedTokenCheckerTest {

    private final JwtProvider provider = new JwtProvider("test-secret-at-least-32-bytes-long-0123456789");
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisRevokedTokenChecker checker = new RedisRevokedTokenChecker(redis);

    private final Claims claims = provider.extractClaims(provider.generateToken("7", Map.of("jti", "abc"), 60_000));

    @Test
    void aLoggedOutJti_isRevoked() {
        when(redis.hasKey("auth:blacklist:abc")).thenReturn(true);

        assertThat(checker.isRevoked(claims)).isTrue();
    }

    @Test
    void aTokenIssuedBeforeTheUsersCutoff_isRevoked() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("auth:blacklist:user:7")).thenReturn(String.valueOf(JwtProvider.issuedAtMillis(claims) + 1));

        assertThat(checker.isRevoked(claims)).isTrue();
    }

    @Test
    void aTokenWithNoRevocation_isNotRevoked() {
        when(redis.opsForValue()).thenReturn(values);

        assertThat(checker.isRevoked(claims)).isFalse();
    }

    /** Documented choice: a Redis outage must not log every user out. */
    @Test
    void redisDown_failsOpen() {
        when(redis.hasKey("auth:blacklist:abc")).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(checker.isRevoked(claims)).isFalse();
    }
}
