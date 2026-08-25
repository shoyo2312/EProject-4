package com.tiktok.userservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The object storage avatars are written to. Deliberately the same bucket and the same property
 * names media-worker uses: an avatar this service stores and one media-worker mirrors from a
 * social provider are the same object under the same key, and two different buckets would mean a
 * profile whose picture depends on which path last wrote it.
 */
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket
) {
}
