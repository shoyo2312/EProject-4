package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.UploadRateLimitProperties;
import com.tiktok.videoservice.exception.UploadRateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * How many uploads one account may start, and how many videos it may publish, per hour and per day
 * — each checked in one Redis round trip. Nothing limited either before: a script could ask for
 * presigned URLs in a loop and fill the bucket, or publish a video a second into every follower's
 * feed.
 *
 * <p>Every tier is checked before any is spent, and every attempt that passes counts, whatever
 * happens next: a publish refused for its caption spends budget too, so the caption rules cannot be
 * probed at speed. The same script as interaction-service's comment limiter.
 *
 * <p>Fails open: Redis being down must not stop every upload on the platform. Fixed windows from each
 * counter's first hit, so a burst at an edge can cross two; the point is the order of magnitude.
 */
@Slf4j
@Component
public class UploadRateLimiter {

    /**
     * Checks every counter, then — only if none is full — increments them all, in one script so two
     * concurrent requests cannot both read "one left". The expiry is re-set whenever it is missing:
     * a key that lost its TTL would otherwise refuse that account for ever. Returns {0, 0} when
     * allowed, else {the 1-based tier that refused, its remaining millis}.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CHECK_THEN_COUNT = RedisScript.of("""
            for i, key in ipairs(KEYS) do
              local hits = tonumber(redis.call('GET', key) or '0')
              if hits >= tonumber(ARGV[2 * i - 1]) then
                local ttl = redis.call('PTTL', key)
                if ttl < 0 then
                  redis.call('PEXPIRE', key, ARGV[2 * i])
                  ttl = tonumber(ARGV[2 * i])
                end
                return {i, ttl}
              end
            end
            for i, key in ipairs(KEYS) do
              local hits = redis.call('INCR', key)
              if hits == 1 or redis.call('PTTL', key) < 0 then
                redis.call('PEXPIRE', key, ARGV[2 * i])
              end
            end
            return {0, 0}
            """, List.class);

    private record Tier(String key, long limit, Duration window) {
    }

    private final StringRedisTemplate redisTemplate;
    private final UploadRateLimitProperties limits;

    public UploadRateLimiter(StringRedisTemplate redisTemplate, UploadRateLimitProperties limits) {
        this.redisTemplate = redisTemplate;
        this.limits = limits;
    }

    /** @throws UploadRateLimitedException carrying how long until the tier that refused frees up */
    public void requireUploadUrl(Long userId) {
        List<Tier> tiers = new ArrayList<>(2);
        add(tiers, "video:upload-rate:url:h:" + userId, limits.uploadUrl().perHour(), Duration.ofHours(1));
        add(tiers, "video:upload-rate:url:d:" + userId, limits.uploadUrl().perDay(), Duration.ofDays(1));
        require(tiers, "upload URL");
    }

    /** @throws UploadRateLimitedException carrying how long until the tier that refused frees up */
    public void requirePublish(Long userId) {
        List<Tier> tiers = new ArrayList<>(2);
        add(tiers, "video:upload-rate:publish:h:" + userId, limits.publish().perHour(), Duration.ofHours(1));
        add(tiers, "video:upload-rate:publish:d:" + userId, limits.publish().perDay(), Duration.ofDays(1));
        require(tiers, "publish");
    }

    private void require(List<Tier> tiers, String what) {
        if (tiers.isEmpty()) {
            return;
        }

        List<String> keys = new ArrayList<>(tiers.size());
        List<String> args = new ArrayList<>(tiers.size() * 2);
        for (Tier tier : tiers) {
            keys.add(tier.key());
            args.add(String.valueOf(tier.limit()));
            args.add(String.valueOf(tier.window().toMillis()));
        }

        List<?> result;
        try {
            result = redisTemplate.execute(CHECK_THEN_COUNT, keys, args.toArray());
        } catch (RuntimeException e) {
            log.warn("Upload rate limit unavailable, allowing the {}: {}", what, e.getMessage());
            return;
        }

        if (result != null && result.size() == 2 && ((Number) result.get(0)).longValue() > 0) {
            throw new UploadRateLimitedException(Duration.ofMillis(Math.max(0, ((Number) result.get(1)).longValue())));
        }
    }

    private static void add(List<Tier> tiers, String key, long limit, Duration window) {
        if (limit > 0) {
            tiers.add(new Tier(key, limit, window));
        }
    }
}
