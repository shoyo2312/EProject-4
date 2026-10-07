package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.UserSettingsCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One account's comment audience in Redis, keyed by user id. The value is the enum name exactly as
 * user-service sent it — this service relays the setting and never interprets it, so there is
 * nothing here to keep in step with user-service's enum.
 *
 * <p>Fails open like {@link VideoCache}: a read that throws is a miss, a write that throws is
 * dropped, and both are logged at debug because they arrive once per request while Redis is down.
 * The cost of Redis being unavailable is a call to user-service per {@code /policy} read, not a
 * 500.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserSettingsCache {

    private static final String KEY_PREFIX = "user-settings:comment-audience:";

    private final StringRedisTemplate redisTemplate;
    private final UserSettingsCacheProperties properties;

    public Optional<String> getCommentAudience(Long userId) {
        if (!properties.enabled()) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key(userId)));
        } catch (Exception e) {
            log.debug("user settings cache read failed for userId={}, asking user-service", userId, e);
            return Optional.empty();
        }
    }

    /**
     * {@link #getCommentAudience} for every owner on a page in one MGET — a GET each was one round
     * trip per owner on every feed page. Owners with no entry are absent from the answer.
     */
    public Map<Long, String> getCommentAudiences(Collection<Long> userIds) {
        if (!properties.enabled() || userIds.isEmpty()) {
            return Map.of();
        }

        try {
            List<Long> ids = List.copyOf(userIds);
            List<String> values = redisTemplate.opsForValue()
                    .multiGet(ids.stream().map(UserSettingsCache::key).toList());
            Map<Long, String> hits = new HashMap<>();
            for (int i = 0; values != null && i < values.size(); i++) {
                if (values.get(i) != null) {
                    hits.put(ids.get(i), values.get(i));
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
    public void putCommentAudiences(Map<Long, String> audiences) {
        if (!properties.enabled() || audiences.isEmpty()) {
            return;
        }

        try {
            Expiration expiry = Expiration.from(properties.ttl());
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                audiences.forEach((userId, audience) -> connection.stringCommands().set(
                        key(userId).getBytes(StandardCharsets.UTF_8),
                        audience.getBytes(StandardCharsets.UTF_8),
                        expiry,
                        SetOption.upsert()));
                return null;
            });
        } catch (Exception e) {
            log.debug("user settings cache write failed for {} users, cache left cold", audiences.size(), e);
        }
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
