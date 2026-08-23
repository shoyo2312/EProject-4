package com.tiktok.interactionservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * A per-(viewer, video, hour) counter, shared by every endpoint that lets one viewer move one
 * video's counter more than once: views, watch sessions, and shares. Each bucket is a separate
 * key so no endpoint eats another's budget.
 *
 * <p>Deliberately loud rather than a silent no-op: a client being throttled has a bug or is being
 * replayed, and both are worth surfacing to whoever wrote it, at the cost of telling a viewer who
 * genuinely replayed sixty times that they were refused.
 *
 * <p>ponytail: a fixed counter per (viewer, video, hour), so a burst at the boundary can cross two
 * windows. A sliding window is the upgrade if that ever matters; it does not here, where the point
 * is the order of magnitude and not the exact number.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionRateLimiter {

    /**
     * How many times one viewer may move one video's counter per window. High enough that a real
     * viewer replaying a video all evening is never refused.
     */
    private static final long MAX_PER_WINDOW = 60;

    private static final Duration WINDOW = Duration.ofHours(1);

    /**
     * The increment and its expiry as one round trip, because they cannot be allowed to come
     * apart. INCR followed by a separate EXPIRE leaves a window — a dropped connection, a
     * failover — where the key exists with no TTL, and a key with no TTL never falls back below
     * the limit: that viewer is refused on that video for ever, with nothing to say why. The TTL
     * is re-checked rather than only set on the first hit for the same reason, so a key that
     * already lost its expiry heals on the next request instead of staying stuck.
     */
    private static final RedisScript<Long> INCREMENT_IN_WINDOW = RedisScript.of("""
            local hits = redis.call('INCR', KEYS[1])
            if hits == 1 or redis.call('TTL', KEYS[1]) < 0 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return hits
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * Fails open. Redis being unreachable throws out of the template rather than returning null,
     * so without this catch an outage would turn every view, watch and share into a 500 — this
     * limit exists to keep a counter honest, not to be a dependency of the endpoints it guards.
     *
     * @param bucket     which budget, e.g. {@code "view-rate"} — part of the key
     * @param onExceeded thrown when the caller is past the limit; supplied by the caller so each
     *                   endpoint reports its own code
     */
    public void require(String bucket, Long videoId, Long userId, Supplier<RuntimeException> onExceeded) {
        String key = "interaction:%s:%d:%d".formatted(bucket, userId, videoId);

        Long hits;
        try {
            hits = redisTemplate.execute(
                    INCREMENT_IN_WINDOW, List.of(key), String.valueOf(WINDOW.toSeconds()));
        } catch (RuntimeException e) {
            log.warn("Rate limit {} unavailable, allowing the request: {}", key, e.getMessage());
            return;
        }

        if (hits != null && hits > MAX_PER_WINDOW) {
            throw onExceeded.get();
        }
    }
}
