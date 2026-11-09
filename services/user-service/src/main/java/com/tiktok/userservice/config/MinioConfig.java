package com.tiktok.userservice.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    /**
     * The service's own calls (avatar and export uploads, removals), in-cluster. Through the public
     * endpoint each one went out through Cloudflare and back in, and a container with no IPv6 route
     * failed outright whenever the connection picked Cloudflare's IPv6 address ("Network is
     * unreachable"), which held a data export for the whole 10-minute lease.
     */
    @Bean
    @Primary
    public MinioClient minioClient(MinioProperties properties,
                                   @Value("${minio.internal-endpoint:${minio.endpoint}}") String internalEndpoint) {
        return client(internalEndpoint, properties);
    }

    /**
     * Presigned GET URLs handed to a browser (export downloads). Those are signed for the host they
     * name, so this one has to be the public address.
     */
    @Bean
    public MinioClient publicMinioClient(MinioProperties properties) {
        return client(properties.endpoint(), properties);
    }

    private static MinioClient client(String endpoint, MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(properties.accessKey(), properties.secretKey())
                .region(properties.region())
                .build();
    }
}
