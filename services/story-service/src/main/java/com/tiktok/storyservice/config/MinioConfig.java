package com.tiktok.storyservice.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties({MinioProperties.class, StoryUploadLimits.class})
public class MinioConfig {

    /**
     * The service's own calls at create (does the upload exist, copy it to the story's key),
     * in-cluster. Through the public endpoint each one went out through Cloudflare and back in, and
     * a container with no IPv6 route failed outright whenever the connection picked Cloudflare's IPv6
     * address: the create answered 503 UPLOAD_URL_UNAVAILABLE although the upload was there.
     */
    @Bean
    @Primary
    public MinioClient minioClient(MinioProperties properties,
                                   @Value("${minio.internal-endpoint:${minio.endpoint}}") String internalEndpoint) {
        return client(internalEndpoint, properties);
    }

    /**
     * Upload forms and presigned GETs handed to apps and browsers. A GET is signed for the host it
     * names, so this one has to be the public address. Signing is local (the region is set), so it
     * never calls MinIO.
     */
    @Bean
    public MinioClient publicMinioClient(MinioProperties properties) {
        return client(properties.endpoint(), properties);
    }

    /** The region is set so signing needs no round trip to MinIO to discover it. */
    private static MinioClient client(String endpoint, MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(properties.accessKey(), properties.secretKey())
                .region(properties.region())
                .build();
    }
}
