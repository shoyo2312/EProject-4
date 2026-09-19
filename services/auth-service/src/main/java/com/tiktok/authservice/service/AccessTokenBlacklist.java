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
        write(RevocationKeys.forJti(jti), "1", ttl);
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
        write(RevocationKeys.forUser(userId), String.valueOf(Instant.now().toEpochMilli()), ttl);
    }

    /**
     * Fails open, like the read side, and for a sharper reason: every caller has already done the
     * thing the revocation accompanies — the password is changed, the account is banned, the
     * replayed refresh chain is revoked in the database. Letting Redis throw here would propagate
     * out and roll that work back, so an unreachable Redis would turn "change my password" into a
     * 500 with the old password still working, and a detected token replay into no revocation at
     * all. Losing early access-token cutoff is the smaller failure: refresh tokens are already
     * dead in the database, so the window is one access-token lifetime rather than forever.
     *
     * <p>Logged at error, not warn: unlike a missed read this leaves a real session alive, and
     * whoever is on call should see it.
     */
    private void write(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.error("Redis unavailable, access tokens stay valid until they expire (key {})", key, e);
        }
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
