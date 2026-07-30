package com.tiktok.interactionservice.repository;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.entity.LikeByVideoKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the lightweight-transaction (IF NOT EXISTS / IF EXISTS) toggle semantics that
 * LikeServiceImpl depends on to avoid double-counting likes on retry — this is the
 * highest-risk untested pattern in the service, per the implementation plan.
 */
class LikeByVideoRepositoryTest extends AbstractInteractionServiceIT {

    @Autowired
    private LikeByVideoRepository likeByVideoRepository;

    @BeforeEach
    void cleanUp() {
        likeByVideoRepository.deleteAll();
    }

    @Test
    void insertIfNotExists_firstCall_returnsTrue() {
        boolean applied = likeByVideoRepository.insertIfNotExists(1L, 100L, Instant.now());

        assertThat(applied).isTrue();
        assertThat(likeByVideoRepository.existsById(LikeByVideoKey.builder().videoId(1L).userId(100L).build())).isTrue();
    }

    @Test
    void insertIfNotExists_duplicateCall_returnsFalse_andDoesNotThrow() {
        likeByVideoRepository.insertIfNotExists(2L, 100L, Instant.now());

        boolean secondApplied = likeByVideoRepository.insertIfNotExists(2L, 100L, Instant.now());

        assertThat(secondApplied).isFalse();
    }

    @Test
    void deleteIfExists_existingRow_returnsTrue_andRemovesRow() {
        likeByVideoRepository.insertIfNotExists(3L, 100L, Instant.now());

        boolean applied = likeByVideoRepository.deleteIfExists(3L, 100L);

        assertThat(applied).isTrue();
        assertThat(likeByVideoRepository.existsById(LikeByVideoKey.builder().videoId(3L).userId(100L).build())).isFalse();
    }

    @Test
    void deleteIfExists_noRow_returnsFalse() {
        boolean applied = likeByVideoRepository.deleteIfExists(4L, 100L);

        assertThat(applied).isFalse();
    }

    @Test
    void likesAreScopedPerVideoAndUser() {
        likeByVideoRepository.insertIfNotExists(5L, 100L, Instant.now());

        boolean appliedForDifferentUser = likeByVideoRepository.insertIfNotExists(5L, 200L, Instant.now());
        boolean appliedForDifferentVideo = likeByVideoRepository.insertIfNotExists(6L, 100L, Instant.now());

        assertThat(appliedForDifferentUser).isTrue();
        assertThat(appliedForDifferentVideo).isTrue();
    }
}
