package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.config.CommentLimitProperties;
import com.tiktok.interactionservice.exception.CommentRateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Every limit on posting a comment, checked in one Redis round trip: a cooldown between two comments,
 * per minute, per hour and per day across all videos, and per hour on one video. Before this only the
 * last existed — 60 an hour on <em>each</em> video — so a bot spreading one comment over a thousand
 * videos never met a limit at all.
 *
 * <p>All tiers are checked before any is spent: a comment refused by the daily tier must not use up
 * a minute's budget too. Every attempt that passes counts, whatever happens to the comment next —
 * one the text rules refuse spends budget, so the blocklist cannot be probed at speed.
 *
 * <p>Fails open, like {@link InteractionRateLimiter}: Redis being down must not stop every comment on
 * the platform. Fixed windows from each counter's first hit, so a burst at an edge can cross two; the
 * point is the order of magnitude.
 */
@Slf4j
@Component
public class CommentRateLimiter {

    /**
     * Checks every counter, then — only if none is full — increments them all, in one script so two
     * concurrent posts cannot both read "one left". The expiry is re-set whenever it is missing, as in
     * InteractionRateLimiter: a key that lost its TTL would otherwise refuse that account for ever.
     * Returns {0, 0} when allowed, else {the 1-based tier that refused, its remaining millis}.
     */
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
    private final CommentLimitProperties.RateLimit limits;

    public CommentRateLimiter(StringRedisTemplate redisTemplate, CommentLimitProperties properties) {
        this.redisTemplate = redisTemplate;
        this.limits = properties.rateLimit();
    }

    /**
     * @throws CommentRateLimitedException carrying how long until the tier that refused frees up
     */
    public void require(Long userId, Long videoId) {
        List<Tier> tiers = tiers(userId, videoId);
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
            log.warn("Comment rate limit unavailable, allowing the comment: {}", e.getMessage());
            return;
        }

        if (result != null && result.size() == 2 && ((Number) result.get(0)).longValue() > 0) {
            throw new CommentRateLimitedException(Duration.ofMillis(Math.max(0, ((Number) result.get(1)).longValue())));
        }
    }

    private List<Tier> tiers(Long userId, Long videoId) {
        List<Tier> tiers = new ArrayList<>(5);
        // A cooldown is a counter allowed one hit per gap.
        add(tiers, "interaction:comment-rate:cooldown:" + userId, 1, limits.cooldown());
        add(tiers, "interaction:comment-rate:m:" + userId, limits.perMinute(), Duration.ofMinutes(1));
        add(tiers, "interaction:comment-rate:h:" + userId, limits.perHour(), Duration.ofHours(1));
        add(tiers, "interaction:comment-rate:d:" + userId, limits.perDay(), Duration.ofDays(1));
        // The key InteractionRateLimiter used for this bucket, so counts carry over a deploy.
        add(tiers, "interaction:comment-rate:%d:%d".formatted(userId, videoId), limits.perVideoPerHour(),
                Duration.ofHours(1));
        return tiers;
    }

    private static void add(List<Tier> tiers, String key, long limit, Duration window) {
        if (limit > 0 && window != null && !window.isZero() && !window.isNegative()) {
            tiers.add(new Tier(key, limit, window));
        }
    }
}
