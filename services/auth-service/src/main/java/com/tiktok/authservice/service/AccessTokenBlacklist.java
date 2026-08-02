package com.tiktok.authservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Revoked access-token ids (jti), stored in Redis with a TTL matching the token's remaining
 * lifetime so entries self-expire and never outlive the token they block.
 *
 * <p>Written on logout, read by this service's own JwtAuthenticationFilter. The same set is read
 * by api-gateway (JwtReactiveAuthenticationManager) and by security-lib's RedisRevokedTokenChecker
 * in downstream services — all read sides fail open if Redis is unreachable, since Redis is
 * best-effort early revocation, not the source of truth for token validity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessTokenBlacklist {

    static final String KEY_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public void blacklist(String jti, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for blacklist check, failing open", e);
            return false;
        }
    }
}
