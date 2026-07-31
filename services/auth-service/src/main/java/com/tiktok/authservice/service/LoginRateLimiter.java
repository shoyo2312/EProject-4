package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TooManyLoginAttemptsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-account failed-login counter, keyed by username/email regardless of source IP —
 * complements the gateway's per-IP RequestRateLimiter, which alone can't stop a distributed
 * brute-force against a single account. In-memory: correct for a single auth-service
 * instance; running multiple replicas would need a shared store (Redis) instead.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    public void checkAllowed(String key) {
        Attempt attempt = attempts.get(normalize(key));
        if (attempt != null && attempt.count.get() >= MAX_ATTEMPTS && !isExpired(attempt)) {
            throw new TooManyLoginAttemptsException();
        }
    }

    public void recordFailure(String key) {
        attempts.compute(normalize(key), (k, existing) -> {
            if (existing == null || isExpired(existing)) {
                return new Attempt(new AtomicInteger(1), Instant.now());
            }
            existing.count.incrementAndGet();
            return existing;
        });
    }

    public void recordSuccess(String key) {
        attempts.remove(normalize(key));
    }

    /** Clears all tracked attempts. Used by tests to isolate state between cases. */
    public void reset() {
        attempts.clear();
    }

    private boolean isExpired(Attempt attempt) {
        return attempt.windowStart.plus(WINDOW).isBefore(Instant.now());
    }

    private String normalize(String key) {
        return key.toLowerCase(Locale.ROOT);
    }

    private record Attempt(AtomicInteger count, Instant windowStart) {
    }
}
