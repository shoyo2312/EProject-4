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

    /**
     * Spends one attempt, before the password is checked, and throws if that was one over the
     * budget. A correct password clears the count again through {@link #recordSuccess}. Taken up
     * front rather than recorded after a failure: see {@link WindowCounter} for the parallel burst
     * that got past a read-then-record limit.
     */
    public void acquireAttempt(String key) {
        Long attempts = failOpen(() -> WindowCounter.increment(redisTemplate, redisKey(key), WINDOW),
                "count login attempt");
        if (attempts != null && attempts > MAX_ATTEMPTS) {
            throw new TooManyLoginAttemptsException();
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

    private String redisKey(String key) {
        return KEY_PREFIX + key.toLowerCase(Locale.ROOT);
    }
}
