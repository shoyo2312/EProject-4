package com.tiktok.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Same bucket media-worker transcodes out of, so {@code bucket} must match its
 * {@code MINIO_BUCKET} — a mismatch means the worker looks for the upload where it was never put.
 *
 * @param endpoint  the MinIO/S3 endpoint the browser will PUT to, so it has to be an address the
 *                  browser can reach, not the in-cluster one
 * @param region    set explicitly so presigning stays a local signature computation: with no
 *                  region the client asks the server for the bucket's location the first time,
 *                  which turns handing out a URL into a network round trip that fails when the
 *                  storage is down. MinIO itself does not care what the value is.
 * @param urlExpiry how long a handed-out upload URL stays usable
 */
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        Duration urlExpiry
) {
}
