package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TooManyLoginAttemptsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Per-account failed-login counter, keyed by username/email regardless of source IP —
 * complements the gateway's per-IP RequestRateLimiter, which alone can't stop a distributed
 * brute-force against a single account.
 *
 * <p>Backed by Redis so the 5-attempt limit holds across auth-service replicas; an in-memory
 * counter would hand an attacker 5 tries per instance. Fails open if Redis is down — login
 * stays available but unthrottled, rather than locking every account out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "auth:login-fail:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public void checkAllowed(String key) {
        String value = failOpen(() -> redisTemplate.opsForValue().get(redisKey(key)), "read login attempts");
        if (value != null && Integer.parseInt(value) >= MAX_ATTEMPTS) {
            throw new TooManyLoginAttemptsException();
        }
    }

    public void recordFailure(String key) {
        String redisKey = redisKey(key);
        Long count = failOpen(() -> redisTemplate.opsForValue().increment(redisKey), "record login failure");
        if (count != null && count == 1L) {
            failOpen(() -> redisTemplate.expire(redisKey, WINDOW), "set login counter window");
        }
    }

    public void recordSuccess(String key) {
        failOpen(() -> redisTemplate.delete(redisKey(key)), "clear login attempts");
    }

    /**
     * Every Redis call here goes through this, because the counter is a throttle and not the
     * authority on whether a login may proceed. An unreachable Redis is treated as a counter that
     * isn't there: brute-force protection is lost for the duration of the outage — the trade the
     * class javadoc describes — which is far cheaper than the alternative, a
     * RedisConnectionFailureException escaping to the catch-all handler and turning every login in
     * the system into a 500.
     *
     * <p>Same choice, same reason as {@link AccessTokenBlacklist#isBlacklisted}. Note that what is
     * wrapped is the Redis call, not the limit check: TooManyLoginAttemptsException is a
     * RuntimeException too, and catching it here would un-throttle exactly the accounts under
     * attack.
     */
    private <T> T failOpen(Supplier<T> operation, String action) {
        try {
            return operation.get();
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, failing open: {}", action, e);
            return null;
        }
    }

    /** Clears all tracked attempts. Used by tests to isolate state between cases. */
    public void reset() {
        var keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String redisKey(String key) {
        return KEY_PREFIX + key.toLowerCase(Locale.ROOT);
    }
}
