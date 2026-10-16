package com.tiktok.videoservice.service;

import com.tiktok.videoservice.client.OwnerCommentSettings;
import com.tiktok.videoservice.config.UserSettingsCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One account's comment settings in Redis, keyed by user id, as JSON — the audience and the comment
 * filters, exactly as user-service sent them. This service relays the settings and never interprets
 * them, so there is nothing here to keep in step with user-service's enum.
 *
 * <p>The key prefix changed when the filters joined the audience ({@code comment-audience:} held a
 * bare enum name): entries under the old prefix are never read again and expire on their own TTL.
 *
 * <p>Fails open like {@link VideoCache}: a read that throws, or a value that does not parse, is a
 * miss; a write that throws is dropped; both are logged at debug because they arrive once per request
 * while Redis is down. The cost of Redis being unavailable is a call to user-service per
 * {@code /policy} read, not a 500.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSettingsCache {

    private static final String KEY_PREFIX = "user-settings:comment-policy:";

    private final StringRedisTemplate redisTemplate;
    private final UserSettingsCacheProperties properties;
    private final ObjectMapper objectMapper;

    public Optional<OwnerCommentSettings> get(Long userId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(parse(redisTemplate.opsForValue().get(key(userId))));
        } catch (Exception e) {
            log.debug("user settings cache read failed for userId={}, asking user-service", userId, e);
            return Optional.empty();
        }
    }

    /**
     * {@link #get} for every owner on a page in one MGET — a GET each was one round trip per owner on
     * every feed page. Owners with no entry are absent from the answer.
     */
    public Map<Long, OwnerCommentSettings> getAll(Collection<Long> userIds) {
        if (!properties.enabled() || userIds.isEmpty()) {
            return Map.of();
        }

        try {
            List<Long> ids = List.copyOf(userIds);
            List<String> values = redisTemplate.opsForValue()
                    .multiGet(ids.stream().map(UserSettingsCache::key).toList());
            Map<Long, OwnerCommentSettings> hits = new HashMap<>();
            for (int i = 0; values != null && i < values.size(); i++) {
                OwnerCommentSettings settings = parse(values.get(i));
                if (settings != null) {
                    hits.put(ids.get(i), settings);
                }
            }
            return hits;
        } catch (Exception e) {
            log.debug("user settings cache read failed for {} users, asking user-service", userIds.size(), e);
            return Map.of();
        }
    }

    /**
     * Every answer from one user-service call in a single pipeline, each with its own expiry —
     * the shape {@link VideoCache#putAll} uses, for the same reason: MSET takes no TTL, and an
     * entry that never expires is a settings change that never shows.
     */
    public void putAll(Map<Long, OwnerCommentSettings> settings) {
        if (!properties.enabled() || settings.isEmpty()) {
            return;
        }

        try {
            Map<byte[], byte[]> entries = new HashMap<>();
            settings.forEach((userId, value) -> entries.put(key(userId).getBytes(StandardCharsets.UTF_8),
                    objectMapper.writeValueAsBytes(value)));
            Expiration expiry = Expiration.from(properties.ttl());
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                entries.forEach((key, value) -> connection.stringCommands().set(key, value, expiry, SetOption.upsert()));
                return null;
            });
        } catch (Exception e) {
            log.debug("user settings cache write failed for {} users, cache left cold", settings.size(), e);
        }
    }

    /** Null for a miss, and for a value that does not parse — which a later write replaces. */
    private OwnerCommentSettings parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, OwnerCommentSettings.class);
        } catch (Exception e) {
            log.debug("unreadable user settings cache entry, treating it as a miss", e);
            return null;
        }
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
