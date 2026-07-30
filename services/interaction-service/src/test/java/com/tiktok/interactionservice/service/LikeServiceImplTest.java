package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.repository.LikeByUserRepository;
import com.tiktok.interactionservice.repository.LikeByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class LikeServiceImplTest extends AbstractInteractionServiceIT {

    @Autowired
    private LikeService likeService;

    @Autowired
    private LikeByVideoRepository likeByVideoRepository;

    @Autowired
    private LikeByUserRepository likeByUserRepository;

    @Autowired
    private VideoCountersRepository videoCountersRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        likeByVideoRepository.deleteAll();
        likeByUserRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void like_newVideo_setsLikedTrueAndIncrementsCount() {
        LikeStatusResponse response = likeService.like(10L, 1L);

        assertThat(response.liked()).isTrue();
        assertThat(response.likeCount()).isEqualTo(1L);
    }

    @Test
    void like_calledTwiceByTheSameUser_isIdempotent() {
        likeService.like(11L, 1L);
        LikeStatusResponse response = likeService.like(11L, 1L);

        assertThat(response.likeCount()).isEqualTo(1L);
    }

    @Test
    void like_thenUnlike_returnsToZero() {
        likeService.like(12L, 1L);

        LikeStatusResponse response = likeService.unlike(12L, 1L);

        assertThat(response.liked()).isFalse();
        assertThat(response.likeCount()).isEqualTo(0L);
    }

    @Test
    void unlike_neverLiked_isNoOpAndStaysAtZero() {
        LikeStatusResponse response = likeService.unlike(13L, 1L);

        assertThat(response.liked()).isFalse();
        assertThat(response.likeCount()).isEqualTo(0L);
    }

    @Test
    void getStatus_reflectsWhetherCurrentUserLiked() {
        likeService.like(14L, 1L);

        assertThat(likeService.getStatus(14L, 1L).liked()).isTrue();
        assertThat(likeService.getStatus(14L, 2L).liked()).isFalse();
    }

    @Test
    void like_isPerUser_countReflectsMultipleLikers() {
        likeService.like(15L, 1L);
        likeService.like(15L, 2L);

        LikeStatusResponse response = likeService.getStatus(15L, 1L);

        assertThat(response.likeCount()).isEqualTo(2L);
    }
}
