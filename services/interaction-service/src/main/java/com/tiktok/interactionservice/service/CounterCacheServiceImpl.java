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

    private final VideoCountersRepository videoCountersRepository;
    private final StringRedisTemplate redisTemplate;

    @Override
    public VideoCounts getCounts(Long videoId) {
        String key = KEY_PREFIX + videoId;
        Map<Object, Object> cached = redisTemplate.opsForHash().entries(key);

        if (!cached.isEmpty()) {
            return new VideoCounts(
                    Long.parseLong((String) cached.get("like")),
                    Long.parseLong((String) cached.get("comment")),
                    Long.parseLong((String) cached.get("share"))
            );
        }

        VideoCounts counts = videoCountersRepository.findById(videoId)
                .map(this::toVideoCounts)
                .orElse(VideoCounts.ZERO);

        redisTemplate.opsForHash().putAll(key, Map.of(
                "like", String.valueOf(counts.likeCount()),
                "comment", String.valueOf(counts.commentCount()),
                "share", String.valueOf(counts.shareCount())
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
                orZero(counters.getShareCount()));
    }

    private long orZero(Long value) {
        return value != null ? value : 0L;
    }
}
