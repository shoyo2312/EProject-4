package com.tiktok.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The owner-settings cache in front of {@code GET /users/internal/settings}, which
 * {@code getPolicy} calls to learn a video owner's comment audience.
 *
 * @param enabled false asks user-service on every {@code /policy} read. Here for the same reason
 *                as {@link VideoCacheProperties#enabled()}: on the day a stale audience is the
 *                leading suspect, turning the cache off should not need a code change.
 * @param ttl     this is the bound on how long a tightened comment audience keeps letting comments
 *                through, so it is deliberately short. No event invalidates it — user-service
 *                publishes nothing when settings change (see docs/user-settings.md §5), and one
 *                minute of staleness on a comment guard is not worth an outbox row, a consumer and
 *                an ordering question. Raise this and that window grows with it.
 */
@ConfigurationProperties(prefix = "app.cache.user-settings")
public record UserSettingsCacheProperties(
        boolean enabled,
        Duration ttl
) {
}
