package com.tiktok.videoservice;

import com.tiktok.common.validation.MediaUrlProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

/**
 * VIA_DTO for the same reason user-service uses it: serializing a PageImpl straight to JSON
 * exposes Spring Data's internal structure, which its own maintainers do not treat as a stable
 * contract (Boot logs a warning on every such response). This pins the paged endpoints —
 * /feed and /users/{userId} — to the documented {@code content} + {@code page} envelope, and
 * makes them match the shape user-service already returns.
 */
@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
@EnableConfigurationProperties(MediaUrlProperties.class)
public class VideoServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(VideoServiceApplication.class, args);
    }
}
