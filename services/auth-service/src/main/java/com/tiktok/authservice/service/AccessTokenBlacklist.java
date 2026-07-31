package com.tiktok.authservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Writes revoked access-token ids (jti) to Redis on logout, keyed with a TTL matching the
 * token's remaining lifetime so entries self-expire and never outlive the token they block.
 * Read side lives in api-gateway (JwtReactiveAuthenticationManager) — see that class for the
 * fail-open behavior if Redis is unreachable at verification time.
 */
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
}
