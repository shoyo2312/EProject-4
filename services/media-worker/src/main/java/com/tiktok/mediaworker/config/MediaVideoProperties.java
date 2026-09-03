package com.tiktok.mediaworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hard limits on an uploaded video. Same env-var names and defaults as video-service's
 * app.upload block, so the storage-edge limit and this backstop cannot drift.
 */
@ConfigurationProperties(prefix = "media.video")
public record MediaVideoProperties(
        long maxBytes,
        int maxDurationSeconds
) {
}
