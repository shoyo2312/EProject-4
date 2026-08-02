package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.mail")
public record MailProperties(
        String from
) {
}
