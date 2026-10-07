package com.tiktok.security.jwt;

import com.tiktok.crypto.jwt.JwtProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    private static final List<String> BOTH_KEYS = List.of("auth:blacklist:user:7", "auth:blacklist:abc");

    @Test
    void aLoggedOutJti_isRevoked() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(BOTH_KEYS)).thenReturn(Arrays.asList(null, "1"));

        assertThat(checker.isRevoked(claims)).isTrue();
    }

    @Test
    void aTokenIssuedBeforeTheUsersCutoff_isRevoked() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(BOTH_KEYS))
                .thenReturn(Arrays.asList(String.valueOf(JwtProvider.issuedAtMillis(claims) + 1), null));

        assertThat(checker.isRevoked(claims)).isTrue();
    }

    @Test
    void aTokenWithNoRevocation_isNotRevoked() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(BOTH_KEYS)).thenReturn(Arrays.asList(null, null));

        assertThat(checker.isRevoked(claims)).isFalse();
    }

    /** Only the user's cutoff can revoke a token minted without a jti. */
    @Test
    void aTokenWithoutJti_isCheckedAgainstTheCutoffAlone() {
        Claims noJti = provider.extractClaims(provider.generateToken("7", Map.of(), 60_000));
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(List.of("auth:blacklist:user:7")))
                .thenReturn(Collections.singletonList(String.valueOf(JwtProvider.issuedAtMillis(noJti) + 1)));

        assertThat(checker.isRevoked(noJti)).isTrue();
    }

    /** Documented choice: a Redis outage must not log every user out. */
    @Test
    void redisDown_failsOpen() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(BOTH_KEYS)).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(checker.isRevoked(claims)).isFalse();
    }
}
