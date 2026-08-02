package com.tiktok.security.jwt;

import com.tiktok.crypto.jwt.JwtProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

/**
 * Auto-configures the servlet-based JWT resource-server wiring shared by every service that
 * only validates tokens issued by auth-service (as opposed to auth-service itself, which
 * issues tokens and has its own JwtProperties/JwtConfig, and api-gateway, which is WebFlux
 * and has no servlet API on its classpath).
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration")
@EnableConfigurationProperties(JwtProperties.class)
public class JwtSecurityAutoConfiguration {

    private static final String INSECURE_DEFAULT_SECRET = "change-this-secret-in-production-please-0123456789";
    private static final int MIN_SECRET_BYTES = 32; // 256 bits, minimum key size accepted for HMAC-SHA signing

    @Bean
    public JwtProvider jwtProvider(JwtProperties properties, Environment environment) {
        validateSecret(properties.secret(), environment);
        return new JwtProvider(properties.secret());
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtProvider jwtProvider,
                                                           RevokedTokenChecker revokedTokenChecker) {
        return new JwtAuthenticationFilter(jwtProvider, revokedTokenChecker);
    }

    /**
     * Fallback for services with no Redis on the classpath (product, order, admin, …): they can't
     * see the logout blacklist, so a token stays usable until it expires. api-gateway still
     * rejects revoked tokens at the edge, which covers all traffic that isn't service-to-service.
     */
    @Bean
    @ConditionalOnMissingBean
    public RevokedTokenChecker revokedTokenChecker() {
        return jti -> false;
    }

    /**
     * Nested member classes are parsed before the enclosing class's @Bean methods, so when Redis
     * is present this registers first and the @ConditionalOnMissingBean no-op above backs off.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    static class RedisRevocationConfiguration {

        @Bean
        RevokedTokenChecker redisRevokedTokenChecker(StringRedisTemplate redisTemplate) {
            return new RedisRevokedTokenChecker(redisTemplate);
        }
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
