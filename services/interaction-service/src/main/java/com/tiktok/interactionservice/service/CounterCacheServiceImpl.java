package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.entity.VideoCounters;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache-aside for video interaction counters. Cassandra's video_counters counter table is
 * the source of truth; Redis only caches reads. On any write, the cache key is deleted
 * (not incremented in place) so the next read lazily reloads the authoritative value from
 * Cassandra — simpler and avoids drift if an increment direction is ever missed.
 *
 * <p>Every Redis call here fails open, the same way {@link InteractionRateLimiter} and
 * ViewServiceImpl's play claim do. This is a cache in front of a database that still has the
 * answer: an outage should cost latency, not correctness. It used to cost more than that —
 * {@link #invalidate} runs inside the compensated block of every like, unlike, share and comment,
 * so a Redis blip threw, the compensation undid the Cassandra write, and a perfectly good like
 * came back as a 500.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CounterCacheServiceImpl implements CounterCacheService {

    private static final String KEY_PREFIX = "interaction:counters:";
    private static final Duration TTL = Duration.ofSeconds(300);
    private static final String SEPARATOR = ":";
    private static final int FIELDS = 6;

    private final VideoCountersRepository videoCountersRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    public VideoCounts getCounts(Long videoId) {
        String key = KEY_PREFIX + videoId;

        VideoCounts cached = readCache(key);
        if (cached != null) {
            return cached;
        }

        VideoCounts counts = videoCountersRepository.findById(videoId)
                .map(this::toVideoCounts)
                .orElse(VideoCounts.ZERO);
        writeCache(key, counts);
        return counts;
    }

    /**
     * {@link #getCounts(Long)} for a page of videos at a fixed cost: one MGET, one Cassandra read
     * for whatever missed (an IN over the partition key), and one pipelined write to cache it.
     * Asked one id at a time, a fifty-video page was up to a hundred and fifty round trips in a row.
     */
    @Override
    public Map<Long, VideoCounts> getCounts(List<Long> videoIds) {
        if (videoIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, VideoCounts> counts = new HashMap<>(readCache(videoIds));
        List<Long> missing = videoIds.stream().filter(videoId -> !counts.containsKey(videoId)).toList();
        if (missing.isEmpty()) {
            return counts;
        }

        Map<Long, VideoCounts> loaded = new HashMap<>();
        videoCountersRepository.findAllById(missing)
                .forEach(row -> loaded.put(row.getVideoId(), toVideoCounts(row)));
        missing.forEach(videoId -> loaded.putIfAbsent(videoId, VideoCounts.ZERO));
        writeCache(loaded);
        counts.putAll(loaded);
        return counts;
    }

    @Override
    public void invalidate(Long videoId) {
        try {
            redisTemplate.delete(KEY_PREFIX + videoId);
        } catch (RuntimeException e) {
            // The counter in Cassandra has already moved, so the only cost is readers seeing the
            // old number until the TTL runs out. Worth far less than failing the write itself.
            log.warn("Could not invalidate the counter cache for video {}: {}", videoId, e.getMessage());
        }
    }

    /**
     * One string, not a hash: it is written with a single SET that carries its own expiry. The
     * hash this replaced needed HSET followed by EXPIRE, and a process that died between the two
     * left a key with no TTL — which the read path never rewrites, because a fully populated key
     * is never a miss. That video's counters then stayed frozen until something invalidated them.
     * {@link InteractionRateLimiter} documents the same hazard and solves it with a script; here
     * there is nothing to script, because the value is one value.
     *
     * @return null for a miss, for anything that does not parse, and for a Redis that is not
     *         answering — all three mean the same thing to the caller: go and ask Cassandra. A
     *         value in the old shape, or one written before a counter was added to this record,
     *         is a mismatched field count and so heals itself on the first read after a deploy.
     */
    private VideoCounts readCache(String key) {
        String cached;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Counter cache unavailable for {}, reading Cassandra: {}", key, e.getMessage());
            return null;
        }
        return parse(cached);
    }

    /** {@link #readCache(String)} for many ids in one MGET: the readable hits, keyed by id. */
    private Map<Long, VideoCounts> readCache(List<Long> videoIds) {
        List<String> values;
        try {
            values = redisTemplate.opsForValue().multiGet(videoIds.stream().map(videoId -> KEY_PREFIX + videoId).toList());
        } catch (RuntimeException e) {
            log.warn("Counter cache unavailable for {} videos, reading Cassandra: {}", videoIds.size(), e.getMessage());
            return Map.of();
        }

        Map<Long, VideoCounts> hits = new HashMap<>();
        for (int i = 0; values != null && i < values.size(); i++) {
            VideoCounts counts = parse(values.get(i));
            if (counts != null) {
                hits.put(videoIds.get(i), counts);
            }
        }
        return hits;
    }

    private static VideoCounts parse(String cached) {
        if (cached == null) {
            return null;
        }

        String[] parts = cached.split(SEPARATOR);
        if (parts.length != FIELDS) {
            return null;
        }
        try {
            return new VideoCounts(
                    Long.parseLong(parts[0]),
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]),
                    Long.parseLong(parts[4]),
                    Long.parseLong(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeCache(String key, VideoCounts counts) {
        try {
            redisTemplate.opsForValue().set(key, format(counts), TTL);
        } catch (RuntimeException e) {
            log.warn("Could not populate the counter cache for {}: {}", key, e.getMessage());
        }
    }

    /**
     * The same SET-with-expiry as {@link #writeCache(String, VideoCounts)} for each entry, sent as
     * one pipeline. Not MSET: it takes no TTL, and a key without one never reloads (see readCache).
     */
    private void writeCache(Map<Long, VideoCounts> counts) {
        try {
            Expiration expiry = Expiration.from(TTL);
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                counts.forEach((videoId, value) -> connection.stringCommands().set(
                        (KEY_PREFIX + videoId).getBytes(StandardCharsets.UTF_8),
                        format(value).getBytes(StandardCharsets.UTF_8),
                        expiry,
                        SetOption.upsert()));
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Could not populate the counter cache for {} videos: {}", counts.size(), e.getMessage());
        }
    }

    private static String format(VideoCounts counts) {
        return "%d%s%d%s%d%s%d%s%d%s%d".formatted(
                counts.likeCount(), SEPARATOR,
                counts.commentCount(), SEPARATOR,
                counts.shareCount(), SEPARATOR,
                counts.viewCount(), SEPARATOR,
                counts.saveCount(), SEPARATOR,
                counts.repostCount());
    }

    private VideoCounts toVideoCounts(VideoCounters counters) {
        return new VideoCounts(
                orZero(counters.getLikeCount()),
                orZero(counters.getCommentCount()),
                orZero(counters.getShareCount()),
                orZero(counters.getViewCount()),
                orZero(counters.getSaveCount()),
                orZero(counters.getRepostCount()));
    }

    private long orZero(Long value) {
        return value != null ? value : 0L;
    }
}
