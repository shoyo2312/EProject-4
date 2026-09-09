package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.otp")
public record OtpProperties(
        long emailVerificationExpiryMillis,
        long passwordResetExpiryMillis,
        /** Second-factor code for admin-console login. Shorter-lived than the others — it is
         *  entered seconds after it is sent, never dug out of an inbox hours later. */
        long adminLoginExpiryMillis
) {
}
