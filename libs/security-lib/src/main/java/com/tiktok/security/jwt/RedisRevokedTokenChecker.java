package com.tiktok.security.jwt;

import com.tiktok.crypto.jwt.RevocationKeys;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Reads the revocation state auth-service writes (see AccessTokenBlacklist there): one key per
 * logged-out token, plus one key per user holding a cutoff instant written when every session
 * had to die at once (password reset, refresh-token replay). A token is revoked if its jti is
 * listed, or if it was issued before its user's cutoff.
 *
 * <p>Fails open: if Redis is unreachable the token is treated as not revoked and accepted on
 * signature/expiry alone, matching api-gateway's behavior — Redis here is best-effort early
 * revocation, not the source of truth for token validity. The cost of a miss is bounded by the
 * access token TTL.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisRevokedTokenChecker implements RevokedTokenChecker {

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean isRevoked(Claims claims) {
        try {
            String jti = RevocationKeys.jtiOf(claims);
            if (jti != null && Boolean.TRUE.equals(redisTemplate.hasKey(RevocationKeys.forJti(jti)))) {
                return true;
            }
            String cutoff = redisTemplate.opsForValue().get(RevocationKeys.forUser(claims.getSubject()));
            return RevocationKeys.isIssuedBefore(claims, cutoff);
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for revocation check, failing open", e);
            return false;
        }
    }
}
