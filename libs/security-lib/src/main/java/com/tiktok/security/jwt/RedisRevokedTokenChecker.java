package com.tiktok.security.jwt;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Reads the revocation set auth-service writes on logout (see AccessTokenBlacklist there).
 *
 * <p>Fails open: if Redis is unreachable the token is treated as not revoked and accepted on
 * signature/expiry alone, matching api-gateway's behavior — Redis here is best-effort early
 * revocation, not the source of truth for token validity. The cost of a miss is bounded by the
 * access token TTL.
 */
@RequiredArgsConstructor
public class RedisRevokedTokenChecker implements RevokedTokenChecker {

    private static final Logger log = LoggerFactory.getLogger(RedisRevokedTokenChecker.class);
    private static final String KEY_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean isRevoked(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for revocation check, failing open", e);
            return false;
        }
    }
}
