package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.config.CommentLimitProperties;
import com.tiktok.interactionservice.exception.DuplicateCommentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DuplicateCommentGuardTest extends AbstractInteractionServiceIT {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private DuplicateCommentGuard guard;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        guard = new DuplicateCommentGuard(redisTemplate, new CommentLimitProperties(
                new CommentLimitProperties.RateLimit(Duration.ZERO, 0, 0, 0, 0),
                new CommentLimitProperties.Duplicate(Duration.ofMinutes(10), 3)));
    }

    @Test
    void theSameTextOnTheSameVideo_isRefused_howeverItIsDressedUp() {
        guard.claim(1L, 10L, null, "Hay quá!!!");

        assertThatThrownBy(() -> guard.claim(1L, 10L, null, "hay   qua"))
                .isInstanceOfSatisfying(DuplicateCommentException.class,
                        e -> assertThat(e.getRetryAfter()).isPositive());
        assertThatCode(() -> guard.claim(1L, 10L, null, "hay thật")).doesNotThrowAnyException();
        assertThatCode(() -> guard.claim(2L, 10L, null, "Hay quá!!!")).doesNotThrowAnyException();
    }

    @Test
    void theSameTextOnTooManyVideos_isRefused() {
        guard.claim(1L, 10L, null, "ghé kênh mình nhé");
        guard.claim(1L, 11L, null, "ghé kênh mình nhé");
        guard.claim(1L, 12L, null, "ghé kênh mình nhé");

        assertThatThrownBy(() -> guard.claim(1L, 13L, null, "ghé kênh mình nhé"))
                .isInstanceOf(DuplicateCommentException.class);
    }

    /** Answering two people the same way is a conversation; answering one of them twice is a copy. */
    @Test
    void theSameReplyToDifferentComments_isNotACopy() {
        guard.claim(1L, 10L, 100L, "đúng rồi");

        assertThatCode(() -> guard.claim(1L, 10L, 101L, "đúng rồi")).doesNotThrowAnyException();
        assertThatCode(() -> guard.claim(1L, 10L, null, "đúng rồi")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.claim(1L, 10L, 100L, "Đúng rồi!"))
                .isInstanceOf(DuplicateCommentException.class);
    }

    /** Pasted under every comment of one video is still pasted: each reply is a copy toward the most. */
    @Test
    void repliesCountTowardTheMostCopies() {
        guard.claim(1L, 10L, 100L, "ghé kênh mình nhé");
        guard.claim(1L, 10L, 101L, "ghé kênh mình nhé");
        guard.claim(1L, 10L, 102L, "ghé kênh mình nhé");

        assertThatThrownBy(() -> guard.claim(1L, 10L, 103L, "ghé kênh mình nhé"))
                .isInstanceOf(DuplicateCommentException.class);
    }

    /** A comment that never got stored must not make its own retry look like a copy. */
    @Test
    void aReleasedClaim_canBeTakenAgain() {
        guard.claim(1L, 10L, null, "lần đầu").release();

        assertThatCode(() -> guard.claim(1L, 10L, null, "lần đầu")).doesNotThrowAnyException();
        assertThat(redisTemplate.opsForValue().get(redisTemplate.keys("interaction:comment-dup:1:*").stream()
                .filter(key -> key.split(":").length == 4).findFirst().orElseThrow())).isEqualTo("1");
    }

    @Test
    void emojiOnlyComments_areOnlyCopiesOfTheSameEmoji() {
        guard.claim(1L, 10L, null, "🔥");

        assertThatCode(() -> guard.claim(1L, 10L, null, "😂")).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.claim(1L, 10L, null, "🔥")).isInstanceOf(DuplicateCommentException.class);
    }

    @Test
    void redisNeverHoldsTheText() {
        guard.claim(1L, 10L, null, "nội dung riêng tư");

        assertThat(redisTemplate.keys("*")).allSatisfy(key -> assertThat(key).doesNotContain("riêng"));
    }
}
