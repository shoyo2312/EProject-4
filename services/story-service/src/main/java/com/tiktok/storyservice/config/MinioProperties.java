package com.tiktok.storyservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Same shape as video-service's: both presign into the one media bucket.
 *
 * @param endpoint  the address presigned GETs are signed for — SigV4 signs the host, so in any
 *                  deployment where browsers are not on the service network this is the public one
 * @param uploadUrl where the browser POSTs uploads to
 * @param urlExpiry lifetime of a presigned upload
 */
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String uploadUrl,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        Duration urlExpiry
) {
}
