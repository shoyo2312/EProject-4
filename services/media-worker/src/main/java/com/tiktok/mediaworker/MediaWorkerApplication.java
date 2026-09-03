package com.tiktok.mediaworker;

import com.tiktok.mediaworker.config.MediaVideoProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MediaVideoProperties.class)
public class MediaWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(MediaWorkerApplication.class, args);
    }
}
