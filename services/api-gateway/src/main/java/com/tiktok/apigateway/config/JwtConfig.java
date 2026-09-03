package com.tiktok.apigateway.config;

import com.tiktok.crypto.jwt.JwtProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;

/**
 * Keep {@link #validateSecret} in step with auth-service's {@code JwtConfig} and security-lib's
 * {@code JwtSecurityAutoConfiguration}, which enforce the same rule for the other two ways a
 * {@link JwtProvider} gets built. Three copies rather than one shared helper only because this
 * module is WebFlux and shares no configuration with either — crypto-lib is where a fourth caller
 * should put it if one ever appears.
 */
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

    /**
     * Refuses to start on the committed placeholder, which every other holder of the secret already
     * did and this one did not. It matters most here: the gateway is the edge, so a deployment that
     * forgot JWT_SECRET came up validating tokens against a secret published in this repository —
     * and the failure has no symptom, because a forged token verifies exactly like a real one.
     */
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
