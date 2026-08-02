package com.tiktok.apigateway.security;

import com.tiktok.crypto.jwt.JwtProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Constructs the manager directly against a real Redis (Testcontainers) instead of a full
 * Spring context — this class has no other collaborators worth bootstrapping a context for.
 */
@Testcontainers
class JwtReactiveAuthenticationManagerTest {

    private static final String SECRET = "test-secret-key-at-least-32-bytes-long!!";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private ReactiveStringRedisTemplate redisTemplate;
    private JwtProvider jwtProvider;
    private JwtReactiveAuthenticationManager manager;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        jwtProvider = new JwtProvider(SECRET);
        manager = new JwtReactiveAuthenticationManager(jwtProvider, redisTemplate);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    private String tokenWithJti(String jti) {
        return jwtProvider.generateToken("42",
                Map.of("role", "USER", "jti", jti, JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS),
                Duration.ofMinutes(15).toMillis());
    }

    @Test
    void authenticate_withValidNonBlacklistedToken_succeeds() {
        String token = tokenWithJti(UUID.randomUUID().toString());
        var authentication = new UsernamePasswordAuthenticationToken(null, token);

        StepVerifier.create(manager.authenticate(authentication))
                .assertNext(result -> {
                    assertThat(result.getPrincipal()).isEqualTo(42L);
                    assertThat(result.getAuthorities())
                            .extracting(Object::toString)
                            .containsExactly("ROLE_USER");
                })
                .verifyComplete();
    }

    @Test
    void authenticate_withBlacklistedJti_rejectsWithBadCredentials() {
        String jti = UUID.randomUUID().toString();
        String token = tokenWithJti(jti);
        redisTemplate.opsForValue().set("auth:blacklist:" + jti, "1", Duration.ofMinutes(1)).block();

        var authentication = new UsernamePasswordAuthenticationToken(null, token);

        StepVerifier.create(manager.authenticate(authentication))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    /**
     * A refresh token is signed with the same secret and lives 7 days — accepting it here would
     * make the 15-minute access token TTL meaningless.
     */
    @Test
    void authenticate_withRefreshToken_rejectsWithBadCredentials() {
        String refreshToken = jwtProvider.generateToken("42",
                Map.of("jti", UUID.randomUUID().toString(),
                        JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_REFRESH),
                Duration.ofDays(7).toMillis());

        var authentication = new UsernamePasswordAuthenticationToken(null, refreshToken);

        StepVerifier.create(manager.authenticate(authentication))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    void authenticate_withInvalidToken_rejectsWithBadCredentials() {
        var authentication = new UsernamePasswordAuthenticationToken(null, "not-a-jwt");

        StepVerifier.create(manager.authenticate(authentication))
                .expectError(BadCredentialsException.class)
                .verify();
    }
}
