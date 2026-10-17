package com.tiktok.mediaworker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param endpoint  where this service itself calls MinIO: the in-cluster address in a deployment,
 *                  so transfers do not leave the host and come back in through Cloudflare
 * @param publicUrl the base of the media URLs written onto videos and avatars, which browsers load
 */
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String publicUrl,
        String accessKey,
        String secretKey,
        String bucket
) {
}
