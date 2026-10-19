package com.tiktok.videoservice.service;

import com.tiktok.videoservice.config.UploadRateLimitProperties;
import com.tiktok.videoservice.exception.UploadRateLimitedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each tier against a real Redis: the Lua is the part a mock would skip. */
@Testcontainers
class UploadRateLimiterTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void flush() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private static UploadRateLimiter limiter(int urlPerHour, int urlPerDay, int publishPerHour, int publishPerDay) {
        return new UploadRateLimiter(redisTemplate, new UploadRateLimitProperties(
                new UploadRateLimitProperties.UploadUrl(urlPerHour, urlPerDay),
                new UploadRateLimitProperties.Publish(publishPerHour, publishPerDay)));
    }

    @Test
    void publishing_pastTheHourlyTier_isRefused_withWhenToTryAgain() {
        UploadRateLimiter limiter = limiter(0, 0, 2, 0);

        limiter.requirePublish(1L);
        limiter.requirePublish(1L);

        assertThatThrownBy(() -> limiter.requirePublish(1L))
                .isInstanceOfSatisfying(UploadRateLimitedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("UPLOAD_RATE_LIMITED");
                    assertThat(e.getRetryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofHours(1));
                    assertThat(e.getHeaders().getFirst("Retry-After")).isNotNull();
                });
        // Another account is not slowed down by it.
        assertThatCode(() -> limiter.requirePublish(2L)).doesNotThrowAnyException();
    }

    @Test
    void theDailyTier_refusesWhenTheHourlyOneWouldNot() {
        UploadRateLimiter limiter = limiter(0, 0, 10, 1);

        limiter.requirePublish(1L);

        assertThatThrownBy(() -> limiter.requirePublish(1L))
                .isInstanceOfSatisfying(UploadRateLimitedException.class,
                        e -> assertThat(e.getRetryAfter()).isGreaterThan(Duration.ofHours(1)));
    }

    /** A refusal by one tier spends nothing from the others. */
    @Test
    void aRefusedRequest_spendsNoBudget() {
        UploadRateLimiter limiter = limiter(0, 0, 1, 5);

        limiter.requirePublish(1L);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> limiter.requirePublish(1L)).isInstanceOf(UploadRateLimitedException.class);
        }

        assertThat(redisTemplate.opsForValue().get("video:upload-rate:publish:d:1")).isEqualTo("1");
    }

    /** Separate budgets: asking for upload URLs does not use up publishing, nor the reverse. */
    @Test
    void uploadUrlsAndPublishing_countSeparately() {
        UploadRateLimiter limiter = limiter(1, 0, 1, 0);

        limiter.requireUploadUrl(1L);
        assertThatCode(() -> limiter.requirePublish(1L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.requireUploadUrl(1L)).isInstanceOf(UploadRateLimitedException.class);
    }

    @Test
    void zero_switchesATierOff() {
        UploadRateLimiter limiter = limiter(0, 0, 0, 0);

        for (int i = 0; i < 20; i++) {
            limiter.requireUploadUrl(1L);
            limiter.requirePublish(1L);
        }
        assertThat(redisTemplate.keys("video:upload-rate:*")).isEmpty();
    }

    /** Redis down must not stop every upload on the platform. */
    @Test
    void anUnreachableRedis_letsTheUploadThrough() {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        nowhere.afterPropertiesSet();
        StringRedisTemplate broken = new StringRedisTemplate(nowhere);
        broken.afterPropertiesSet();
        UploadRateLimiter limiter = new UploadRateLimiter(broken, new UploadRateLimitProperties(
                new UploadRateLimitProperties.UploadUrl(1, 1), new UploadRateLimitProperties.Publish(1, 1)));

        try {
            assertThatCode(() -> limiter.requirePublish(1L)).doesNotThrowAnyException();
            assertThatCode(() -> limiter.requirePublish(1L)).doesNotThrowAnyException();
        } finally {
            nowhere.destroy();
        }
    }
}
