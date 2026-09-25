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
 * @param ttl     the bound on how long a changed comment audience takes to show. It used to be
 *                30s, which was fine for the comment guard alone — but every video now carries
 *                {@code accountCommentsOff}, so a stale entry is visible to the owner who has just
 *                changed the setting and reloads to check, and half a minute of that reads as a
 *                bug. A few seconds still collapses the burst of reads one page load makes. No
 *                event invalidates it: user-service's outbox polls every 5s, so an event would not
 *                arrive sooner than this expires (see docs/user-settings.md §5).
 */
@ConfigurationProperties(prefix = "app.cache.user-settings")
public record UserSettingsCacheProperties(
        boolean enabled,
        Duration ttl
) {
}
