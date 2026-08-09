package com.tiktok.authservice.service;

import com.tiktok.crypto.jwt.RevocationKeys;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

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

    private final StringRedisTemplate redisTemplate;

    public void blacklist(String jti, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue().set(RevocationKeys.forJti(jti), "1", ttl);
    }

    /**
     * Kills every access token already issued to a user, without knowing their ids: stores a
     * cutoff instant that read sides compare each token's {@code iat} against. Used when a
     * session must die everywhere at once — password reset, refresh-token replay.
     *
     * <p>The TTL is the access-token lifetime, so the key disappears once no token predating the
     * cutoff can still be alive.
     */
    public void blacklistAllForUser(Long userId, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redisTemplate.opsForValue()
                .set(RevocationKeys.forUser(userId), String.valueOf(Instant.now().toEpochMilli()), ttl);
    }

    /**
     * Whether this token was revoked before its natural expiry — either individually (logout) or
     * as part of a user-wide cutoff. Fails open when Redis is unreachable, matching the other
     * read sides; see the class javadoc.
     */
    public boolean isBlacklisted(Claims claims) {
        try {
            String jti = RevocationKeys.jtiOf(claims);
            if (jti != null && Boolean.TRUE.equals(redisTemplate.hasKey(RevocationKeys.forJti(jti)))) {
                return true;
            }
            String cutoff = redisTemplate.opsForValue().get(RevocationKeys.forUser(claims.getSubject()));
            return RevocationKeys.isIssuedBefore(claims, cutoff);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for blacklist check, failing open", e);
            return false;
        }
    }
}
