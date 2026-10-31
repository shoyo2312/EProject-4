package com.tiktok.videoservice.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    /**
     * The service's own calls, in-cluster. Through the public endpoint each statObject went out
     * through Cloudflare and back in through the tunnel — 0.7–5 s apiece, one per quarantined row
     * of an admin listing, which held the console's dashboard and review queue at 6–13 s. The
     * upload form's POST policy is signed over its fields, not the host, so it is safe to sign
     * here too: the browser still posts it to {@code minio.upload-url}.
     */
    @Bean
    @Primary
    public MinioClient minioClient(MinioProperties properties,
                                   @Value("${minio.internal-endpoint:${minio.endpoint}}") String internalEndpoint) {
        return client(internalEndpoint, properties);
    }

    /**
     * Presigned GET URLs handed to a browser. Those are signed for the host they name, so this one
     * has to be the public address.
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
