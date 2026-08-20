package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.entity.VideoCounters;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * Cache-aside for video interaction counters. Cassandra's video_counters counter table is
 * the source of truth; Redis only caches reads. On any write, the cache key is deleted
 * (not incremented in place) so the next read lazily reloads the authoritative value from
 * Cassandra — simpler and avoids drift if an increment direction is ever missed.
 */
@Service
@RequiredArgsConstructor
public class CounterCacheServiceImpl implements CounterCacheService {

    private static final String KEY_PREFIX = "interaction:counters:";
    private static final Duration TTL = Duration.ofSeconds(300);
    private static final int FIELDS = 4;

    private final VideoCountersRepository videoCountersRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    public VideoCounts getCounts(Long videoId) {
        String key = KEY_PREFIX + videoId;
        Map<Object, Object> cached = redisTemplate.opsForHash().entries(key);

        // Full hash only. A hash written before a counter was added to this record is missing
        // that field, and reading it back would parse null; treating the short hash as a miss
        // reloads from Cassandra and rewrites the key, so a deploy heals itself on first read.
        if (cached.size() == FIELDS) {
            return new VideoCounts(
                    Long.parseLong((String) cached.get("like")),
                    Long.parseLong((String) cached.get("comment")),
                    Long.parseLong((String) cached.get("share")),
                    Long.parseLong((String) cached.get("view"))
            );
        }

        VideoCounts counts = videoCountersRepository.findById(videoId)
                .map(this::toVideoCounts)
                .orElse(VideoCounts.ZERO);

        redisTemplate.opsForHash().putAll(key, Map.of(
                "like", String.valueOf(counts.likeCount()),
                "comment", String.valueOf(counts.commentCount()),
                "share", String.valueOf(counts.shareCount()),
                "view", String.valueOf(counts.viewCount())
        ));
        redisTemplate.expire(key, TTL);

        return counts;
    }

    @Override
    public void invalidate(Long videoId) {
        redisTemplate.delete(KEY_PREFIX + videoId);
    }

    private VideoCounts toVideoCounts(VideoCounters counters) {
        return new VideoCounts(
                orZero(counters.getLikeCount()),
                orZero(counters.getCommentCount()),
                orZero(counters.getShareCount()),
                orZero(counters.getViewCount()));
    }

    private long orZero(Long value) {
        return value != null ? value : 0L;
    }
}
