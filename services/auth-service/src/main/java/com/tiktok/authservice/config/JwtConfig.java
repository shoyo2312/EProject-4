package com.tiktok.authservice.config;

import com.tiktok.crypto.jwt.JwtProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final String INSECURE_DEFAULT_SECRET = "change-this-secret-in-production-please-0123456789";
    private static final int MIN_SECRET_BYTES = 32; // 256 bits, minimum key size accepted for HMAC-SHA signing

    @Bean
    public JwtProvider jwtProvider(JwtProperties properties, Environment environment) {
        validateSecret(properties.secret(), environment);
        return new JwtProvider(properties.secret());
    }

    private void validateSecret(String secret, Environment environment) {
        if (environment.matchesProfiles("local", "test")) {
            return;
        }
        if (INSECURE_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "JWT_SECRET is set to the insecure default value. Set a real secret via the JWT_SECRET env var.");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes (256 bits) for HMAC signing.");
        }
    }
}
