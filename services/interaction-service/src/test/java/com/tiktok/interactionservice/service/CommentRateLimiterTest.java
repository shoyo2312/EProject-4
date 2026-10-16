package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.config.CommentLimitProperties;
import com.tiktok.interactionservice.exception.CommentRateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each tier on its own, against a real Redis: the test profile switches them all off for the rest. */
class CommentRateLimiterTest extends AbstractInteractionServiceIT {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void flush() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    private CommentRateLimiter limiter(Duration cooldown, int perMinute, int perHour, int perDay, int perVideo) {
        return new CommentRateLimiter(redisTemplate, new CommentLimitProperties(
                new CommentLimitProperties.RateLimit(cooldown, perMinute, perHour, perDay, perVideo),
                new CommentLimitProperties.Duplicate(Duration.ZERO, 0)));
    }

    @Test
    void cooldown_refusesTheSecondCommentInsideTheGap_andSaysHowLongToWait() {
        CommentRateLimiter limiter = limiter(Duration.ofSeconds(30), 0, 0, 0, 0);

        limiter.require(1L, 10L);

        assertThatThrownBy(() -> limiter.require(1L, 11L))
                .isInstanceOfSatisfying(CommentRateLimitedException.class, e -> {
                    assertThat(e.getRetryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(30));
                    assertThat(e.getHeaders().getFirst("Retry-After")).isNotNull();
                });
        // Somebody else is not slowed down by it.
        assertThatCode(() -> limiter.require(2L, 10L)).doesNotThrowAnyException();
    }

    /** The gap the per-video tier left: one comment on each of many videos. */
    @Test
    void theHourlyTier_countsEveryVideo() {
        CommentRateLimiter limiter = limiter(Duration.ZERO, 0, 3, 0, 60);

        limiter.require(1L, 10L);
        limiter.require(1L, 11L);
        limiter.require(1L, 12L);

        assertThatThrownBy(() -> limiter.require(1L, 13L)).isInstanceOf(CommentRateLimitedException.class);
    }

    @Test
    void thePerVideoTier_countsOneVideo() {
        CommentRateLimiter limiter = limiter(Duration.ZERO, 0, 0, 0, 2);

        limiter.require(1L, 10L);
        limiter.require(1L, 10L);

        assertThatThrownBy(() -> limiter.require(1L, 10L)).isInstanceOf(CommentRateLimitedException.class);
        assertThatCode(() -> limiter.require(1L, 11L)).doesNotThrowAnyException();
    }

    /** A post refused by one tier spends nothing from the others. */
    @Test
    void aRefusedComment_spendsNoTier() {
        CommentRateLimiter limiter = limiter(Duration.ZERO, 5, 0, 0, 1);

        limiter.require(1L, 10L);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> limiter.require(1L, 10L)).isInstanceOf(CommentRateLimitedException.class);
        }

        assertThat(redisTemplate.opsForValue().get("interaction:comment-rate:m:1")).isEqualTo("1");
    }

    @Test
    void everyCounter_expires() {
        limiter(Duration.ofSeconds(3), 10, 100, 500, 60).require(1L, 10L);

        for (String key : redisTemplate.keys("interaction:comment-rate:*")) {
            assertThat(redisTemplate.getExpire(key)).as(key).isPositive();
        }
    }

    /** A counter that lost its TTL would refuse that account for ever; the next refusal heals it. */
    @Test
    void aFullCounterWithNoExpiry_isGivenOneAgain() {
        redisTemplate.opsForValue().set("interaction:comment-rate:d:1", "5");
        CommentRateLimiter limiter = limiter(Duration.ZERO, 0, 0, 5, 0);

        assertThatThrownBy(() -> limiter.require(1L, 10L)).isInstanceOf(CommentRateLimitedException.class);

        assertThat(redisTemplate.getExpire("interaction:comment-rate:d:1")).isPositive();
    }

    @Test
    void tiersSetToZero_areOff() {
        CommentRateLimiter limiter = limiter(Duration.ZERO, 0, 0, 0, 0);

        for (int i = 0; i < 20; i++) {
            limiter.require(1L, 10L);
        }
        assertThat(redisTemplate.keys("interaction:comment-rate:*")).isEmpty();
    }
}
