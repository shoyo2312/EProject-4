package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
@EnableConfigurationProperties({OtpProperties.class, MailProperties.class, RetentionProperties.class, TurnstileProperties.class})
public class AsyncConfig {
}
