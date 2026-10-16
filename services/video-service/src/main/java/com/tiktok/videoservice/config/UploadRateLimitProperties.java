package com.tiktok.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How many uploads one account may start and publish. Zero switches a tier off — the integration
 * tests switch them off to publish in a loop, and an operator can do the same for one tier without
 * a deploy.
 *
 * @param uploadUrl {@code POST /videos/upload-url}: each presigned POST lets its holder put a file of
 *                  up to {@code app.upload.max-bytes} into storage, published or not — this is the
 *                  tier that keeps one account from filling the bucket with files nothing points at
 * @param publish   {@code POST /videos}: each one costs a transcode and a moderation pass, and lands
 *                  in followers' feeds
 */
@ConfigurationProperties(prefix = "app.upload.rate-limit")
public record UploadRateLimitProperties(
        @DefaultValue UploadUrl uploadUrl,
        @DefaultValue Publish publish
) {

    /** Higher than publishing: a failed or abandoned upload is retried with a new URL. */
    public record UploadUrl(
            @DefaultValue("30") int perHour,
            @DefaultValue("100") int perDay
    ) {
    }

    public record Publish(
            @DefaultValue("10") int perHour,
            @DefaultValue("30") int perDay
    ) {
    }
}
