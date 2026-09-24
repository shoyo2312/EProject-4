package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.UserSettingsCacheProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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

    public void putCommentAudience(Long userId, String commentAudience) {
        if (!properties.enabled()) {
            return;
        }

        try {
            redisTemplate.opsForValue().set(key(userId), commentAudience, properties.ttl());
        } catch (Exception e) {
            log.debug("user settings cache write failed for userId={}, cache left cold", userId, e);
        }
    }

    private static String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
