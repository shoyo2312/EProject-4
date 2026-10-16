package com.tiktok.authservice.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

/**
 * A fixed-window counter in Redis, shared by the login and OTP limiters.
 *
 * <p>The count comes back from the same increment that records the attempt. The limiters used to
 * read the counter first and increment it only after the password or code had been checked, so
 * every request in a parallel burst read the same count and got through: a few hundred concurrent
 * submissions were a few hundred guesses against a budget of five. With the increment as the
 * check, only the first requests of a burst are under the limit, however many arrive together.
 */
final class WindowCounter {

    /**
     * The increment and its expiry in one round trip, the same script as interaction-service's
     * InteractionRateLimiter. INCR followed by a separate EXPIRE can leave the key without a TTL
     * if the connection drops in between, and a counter with no TTL never drops back under the
     * limit, which locks that account out for good. The TTL is re-checked on every hit so such a
     * key heals on the next attempt.
     */
    private static final RedisScript<Long> INCREMENT_IN_WINDOW = RedisScript.of("""
            local hits = redis.call('INCR', KEYS[1])
            if hits == 1 or redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return hits
            """, Long.class);

    private WindowCounter() {
    }

    /** Counts one hit and returns the total so far in the current window. */
    static Long increment(StringRedisTemplate redisTemplate, String key, Duration window) {
        return redisTemplate.execute(INCREMENT_IN_WINDOW, List.of(key), String.valueOf(window.toSeconds()));
    }
}
