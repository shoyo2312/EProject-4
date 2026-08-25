package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.repository.LikeByUserRepository;
import com.tiktok.interactionservice.repository.LikeByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

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

    /**
     * The counter moves before the event is published, so a publish that fails has to take the
     * increment back as well as the like row. Leaving the increment standing while releasing the
     * claim is what made the client's retry count the same like twice.
     */
    @Test
    void like_whenThePublishFails_leavesNeitherTheRowNorTheCount() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new IllegalStateException("broker refused the record"));

        assertThatThrownBy(() -> likeService.like(16L, 1L)).isInstanceOf(IllegalStateException.class);

        reset(kafkaTemplate);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        LikeStatusResponse afterRetry = likeService.like(16L, 1L);
        assertThat(afterRetry.likeCount()).isEqualTo(1L);
    }

    @Test
    void like_isPerUser_countReflectsMultipleLikers() {
        likeService.like(15L, 1L);
        likeService.like(15L, 2L);

        LikeStatusResponse response = likeService.getStatus(15L, 1L);

        assertThat(response.likeCount()).isEqualTo(2L);
    }

    @Test
    void listLikedVideos_returnsOnlyTheCallersLikesAndPages() {
        likeService.like(40L, 1L);
        likeService.like(41L, 1L);
        likeService.like(42L, 2L);

        VideoIdPageResponse first = likeService.listLikedVideos(1L, null, 1);
        assertThat(first.videoIds()).hasSize(1);
        assertThat(first.hasMore()).isTrue();

        VideoIdPageResponse second = likeService.listLikedVideos(1L, first.nextCursor(), 1);
        assertThat(first.videoIds()).doesNotContainAnyElementsOf(second.videoIds());
        assertThat(second.videoIds()).hasSize(1);
        assertThat(first.videoIds().get(0) + second.videoIds().get(0)).isEqualTo(81L);
    }

    @Test
    void listLikedVideos_ordersByWhenItWasLikedNotByVideoId() {
        // 50 is the newer video, liked first; 44 is the older one, liked second. Clustering on
        // video_id would put 50 on top — the listing has to put 44 there.
        likeService.like(50L, 1L);
        likeService.like(44L, 1L);

        assertThat(likeService.listLikedVideos(1L, null, 20).videoIds()).containsExactly(44L, 50L);

        // The same two videos liked the other way round, so neither ordering of video_id can
        // produce both expectations and the assertion is actually about the like time.
        likeService.like(44L, 2L);
        likeService.like(50L, 2L);

        assertThat(likeService.listLikedVideos(2L, null, 20).videoIds()).containsExactly(50L, 44L);
    }

    @Test
    void listLikedVideos_afterUnlike_dropsTheVideo() {
        likeService.like(43L, 1L);
        likeService.unlike(43L, 1L);

        assertThat(likeService.listLikedVideos(1L, null, 20).videoIds()).isEmpty();
    }
}
