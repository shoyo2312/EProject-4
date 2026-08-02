package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TooManyLoginAttemptsException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Per-account failed-login counter, keyed by username/email regardless of source IP —
 * complements the gateway's per-IP RequestRateLimiter, which alone can't stop a distributed
 * brute-force against a single account.
 *
 * <p>Backed by Redis so the 5-attempt limit holds across auth-service replicas; an in-memory
 * counter would hand an attacker 5 tries per instance. Fails open if Redis is down — login
 * stays available but unthrottled, rather than locking every account out.
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "auth:login-fail:";
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public void checkAllowed(String key) {
        String value = redisTemplate.opsForValue().get(redisKey(key));
        if (value != null && Integer.parseInt(value) >= MAX_ATTEMPTS) {
            throw new TooManyLoginAttemptsException();
        }
    }

    public void recordFailure(String key) {
        String redisKey = redisKey(key);
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, WINDOW);
        }
    }

    public void recordSuccess(String key) {
        redisTemplate.delete(redisKey(key));
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
