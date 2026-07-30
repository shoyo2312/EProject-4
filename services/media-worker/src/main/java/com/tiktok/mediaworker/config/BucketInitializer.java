package com.tiktok.mediaworker.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the media bucket on startup so a fresh MinIO container (no manual setup) works
 * out of the box in local/dev environments.
 */
@Component
@RequiredArgsConstructor
public class BucketInitializer implements ApplicationRunner {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
        }
    }
}
