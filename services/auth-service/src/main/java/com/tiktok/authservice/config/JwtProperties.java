package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpiryMillis,
        long refreshTokenExpiryMillis
) {
}
