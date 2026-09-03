package com.tiktok.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upload ceilings. {@code maxBytes} is signed into the presigned POST policy so MinIO rejects
 * an oversize file itself; {@code maxDurationSeconds} is not enforced here (this service never
 * sees the bytes) — media-worker is the backstop for both. Same env vars as media-worker's
 * media.video block.
 */
@ConfigurationProperties(prefix = "app.upload")
public record UploadLimitProperties(
        long maxBytes,
        int maxDurationSeconds
) {
}
