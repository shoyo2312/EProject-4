package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.turnstile")
public record TurnstileProperties(
        String secretKey
) {
}
