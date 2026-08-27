package com.tiktok.videoservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.videoservice.config.VideoCacheProperties;
import com.tiktok.videoservice.dto.response.VideoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Video metadata in Redis, in front of the two reads a feed scroll repeats: one video by id, and
 * a ranking's worth of ids in a batch. Mongo stays the source of truth — nothing here is written
 * that was not read from it first, and every entry expires.
 *
 * <p>What is stored is {@link VideoResponse}, not the {@code Video} entity. The entity carries
 * only {@code @Getter}, so Jackson has no way to rebuild one, while the record is restored
 * through its canonical constructor; and the response already carries {@code userId},
 * {@code status} and {@code visibility}, which is everything the caller's visibility check needs.
 * That check therefore runs on a cached value exactly as it runs on a freshly read one — which is
 * why the key holds no requester id: one entry serves every viewer, and who may see it is decided
 * after the read, not by which key was looked up.
 *
 * <p>Deleted videos are never stored: what gets cached comes from queries that already exclude
 * them. A soft delete evicts rather than caching a tombstone, so a deleted id simply misses
 * forever — a fine trade while nothing asks for deleted ids in a loop.
 *
 * <p><strong>Every method here fails open.</strong> Redis being down must cost a Mongo query, not
 * a 500: a read that throws is reported as a miss, and a write that throws is dropped. The
 * exceptions are logged at debug because they arrive once per request while Redis is down, and a
 * warn-per-request is how a cache outage becomes a log outage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoCache {

    private static final String KEY_PREFIX = "video:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final VideoCacheProperties properties;

    public Optional<VideoResponse> get(String videoId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }

        try {
            String json = redisTemplate.opsForValue().get(key(videoId));
            if (json == null) {
                log.debug("video cache MISS videoId={}", videoId);
                return Optional.empty();
            }

            log.debug("video cache HIT videoId={}", videoId);
            return Optional.of(objectMapper.readValue(json, VideoResponse.class));
        } catch (Exception e) {
            log.debug("video cache read failed for videoId={}, falling back to Mongo", videoId, e);
            return Optional.empty();
        }
    }

    /**
     * One MGET for the whole batch rather than a GET per id, so a fifty-video ranking costs one
     * round trip instead of fifty. Ids the cache does not hold are simply absent from the result;
     * the caller reads a whole batch of misses the same way it reads a partial one.
     *
     * <p>An entry that fails to parse is skipped rather than failing the batch — the id it
     * belongs to becomes a miss and is fetched from Mongo, which is what a stale serialization
     * format after a deploy should cost.
     */
    public Map<String, VideoResponse> getAll(List<String> videoIds) {
        if (!properties.enabled() || videoIds.isEmpty()) {
            return Map.of();
        }

        try {
            List<String> keys = videoIds.stream().map(VideoCache::key).toList();
            List<String> values = redisTemplate.opsForValue().multiGet(keys);
            if (values == null) {
                return Map.of();
            }

            Map<String, VideoResponse> hits = new HashMap<>();
            for (int i = 0; i < videoIds.size() && i < values.size(); i++) {
                String json = values.get(i);
                if (json == null) {
                    continue;
                }
                try {
                    hits.put(videoIds.get(i), objectMapper.readValue(json, VideoResponse.class));
                } catch (Exception e) {
                    log.debug("video cache entry unreadable for videoId={}", videoIds.get(i), e);
                }
            }

            log.debug("video cache batch: {} hit, {} miss", hits.size(), videoIds.size() - hits.size());
            return hits;
        } catch (Exception e) {
            log.debug("video cache batch read failed for {} ids, falling back to Mongo", videoIds.size(), e);
            return Map.of();
        }
    }

    public void put(VideoResponse video) {
        putAll(List.of(video));
    }

    /**
     * Written one SET at a time inside a single pipeline rather than as an MSET: MSET takes no
     * expiry, and a batch of entries that never expire is the one outcome this cache must not
     * have.
     */
    public void putAll(List<VideoResponse> videos) {
        if (!properties.enabled() || videos.isEmpty()) {
            return;
        }

        try {
            List<String> payloads = new ArrayList<>(videos.size());
            for (VideoResponse video : videos) {
                payloads.add(objectMapper.writeValueAsString(video));
            }

            Expiration expiry = Expiration.from(properties.ttl());
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                for (int i = 0; i < videos.size(); i++) {
                    connection.stringCommands().set(
                            key(videos.get(i).id()).getBytes(StandardCharsets.UTF_8),
                            payloads.get(i).getBytes(StandardCharsets.UTF_8),
                            expiry,
                            SetOption.upsert());
                }
                return null;
            });
        } catch (Exception e) {
            log.debug("video cache write failed for {} videos, cache left cold", videos.size(), e);
        }
    }

    /**
     * For the changes a TTL must not be allowed to outlast: a soft delete, and the status moves
     * (transcode finished, taken down, restored) that decide whether a video is on a read path at
     * all. Counter increments deliberately do not evict — see {@link VideoCacheProperties#ttl()}.
     */
    public void evict(String videoId) {
        if (!properties.enabled()) {
            return;
        }

        try {
            redisTemplate.delete(key(videoId));
        } catch (Exception e) {
            log.debug("video cache evict failed for videoId={}, entry left to expire", videoId, e);
        }
    }

    private static String key(String videoId) {
        return KEY_PREFIX + videoId;
    }
}
