package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.otp")
public record OtpProperties(
        long emailVerificationExpiryMillis,
        long passwordResetExpiryMillis
) {
}
