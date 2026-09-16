package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.RepostContextResponse;
import com.tiktok.interactionservice.dto.response.RepostStatusResponse;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.exception.RepostRateLimitedException;
import com.tiktok.interactionservice.repository.RepostByUserRepository;
import com.tiktok.interactionservice.repository.RepostByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@link LikeServiceImplTest} — repost is the same toggle shape end to end (claim row,
 * reverse index, counter, published event), so the cases that matter are the same ones.
 */
class RepostServiceImplTest extends AbstractInteractionServiceIT {

    @Autowired
    private RepostService repostService;

    @Autowired
    private RepostByVideoRepository repostByVideoRepository;

    @Autowired
    private RepostByUserRepository repostByUserRepository;

    @Autowired
    private VideoCountersRepository videoCountersRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CounterCacheService counterCacheService;

    @BeforeEach
    void cleanUp() {
        repostByVideoRepository.deleteAll();
        repostByUserRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void repost_newVideo_setsRepostedTrueAndIncrementsCount() {
        RepostStatusResponse response = repostService.repost(10L, 1L);

        assertThat(response.reposted()).isTrue();
        assertThat(response.repostCount()).isEqualTo(1L);
    }

    @Test
    void repost_calledTwiceByTheSameUser_isIdempotent() {
        repostService.repost(11L, 1L);
        RepostStatusResponse response = repostService.repost(11L, 1L);

        assertThat(response.repostCount()).isEqualTo(1L);
    }

    @Test
    void repost_thenUnrepost_returnsToZero() {
        repostService.repost(12L, 1L);

        RepostStatusResponse response = repostService.unrepost(12L, 1L);

        assertThat(response.reposted()).isFalse();
        assertThat(response.repostCount()).isEqualTo(0L);
    }

    @Test
    void unrepost_neverReposted_isNoOpAndStaysAtZero() {
        RepostStatusResponse response = repostService.unrepost(13L, 1L);

        assertThat(response.reposted()).isFalse();
        assertThat(response.repostCount()).isEqualTo(0L);
    }

    @Test
    void repost_isPerUser_countReflectsMultipleReposters() {
        repostService.repost(15L, 1L);
        repostService.repost(15L, 2L);

        assertThat(repostService.repost(15L, 1L).repostCount()).isEqualTo(2L);
    }

    /** The badge's whole input: who reposted, and whether the viewer is one of them. */
    @Test
    void getContexts_returnsOneEntryPerIdWithTheRepostersOfEach() {
        repostService.repost(60L, 1L);
        repostService.repost(60L, 2L);
        repostService.repost(61L, 2L);

        List<RepostContextResponse> contexts = repostService.getContexts(List.of(61L, 60L, 62L), 1L);

        assertThat(contexts).extracting(RepostContextResponse::videoId).containsExactly(61L, 60L, 62L);
        assertThat(contexts).extracting(RepostContextResponse::repostedByMe)
                .containsExactly(false, true, false);
        assertThat(contexts.get(0).reposterIds()).containsExactly(2L);
        assertThat(contexts.get(1).reposterIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(contexts.get(2).reposterIds()).isEmpty();
    }

    @Test
    void getContexts_afterUnrepost_dropsTheReposter() {
        repostService.repost(63L, 1L);
        repostService.unrepost(63L, 1L);

        RepostContextResponse context = repostService.getContexts(List.of(63L), 1L).get(0);

        assertThat(context.repostedByMe()).isFalse();
        assertThat(context.reposterIds()).isEmpty();
    }

    @Test
    void listReposts_returnsOnlyTheCallersRepostsAndPages() {
        repostService.repost(40L, 1L);
        repostService.repost(41L, 1L);
        repostService.repost(42L, 2L);

        VideoIdPageResponse first = repostService.listReposts(1L, null, 1);
        assertThat(first.videoIds()).hasSize(1);
        assertThat(first.hasMore()).isTrue();

        VideoIdPageResponse second = repostService.listReposts(1L, first.nextCursor(), 1);
        assertThat(first.videoIds()).doesNotContainAnyElementsOf(second.videoIds());
        assertThat(first.videoIds().get(0) + second.videoIds().get(0)).isEqualTo(81L);
    }

    @Test
    void listReposts_afterUnrepost_dropsTheVideo() {
        repostService.repost(43L, 1L);
        repostService.unrepost(43L, 1L);

        assertThat(repostService.listReposts(1L, null, 20).videoIds()).isEmpty();
    }

    /**
     * The counter moves before the event is published, so a publish that fails has to take the
     * increment, the claim and the listing row back — otherwise the client's retry counts twice
     * and the Reposts tab shows a video the badge says was never reposted.
     */
    @Test
    void repost_whenThePublishFails_leavesNeitherTheRowNorTheCount() {
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new IllegalStateException("broker refused the record"));

        assertThatThrownBy(() -> repostService.repost(16L, 1L)).isInstanceOf(IllegalStateException.class);

        assertThat(repostService.listReposts(1L, null, 20).videoIds()).isEmpty();
        assertThat(repostService.getContexts(List.of(16L), 1L).get(0).repostedByMe()).isFalse();

        reset(kafkaTemplate);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        assertThat(repostService.repost(16L, 1L).repostCount()).isEqualTo(1L);
    }

    /** The mirror image: a failed un-repost must not drop the video it half-removed. */
    @Test
    void unrepost_whenThePublishFails_keepsTheRepost() {
        repostService.repost(18L, 1L);

        reset(kafkaTemplate);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenThrow(new IllegalStateException("broker refused the record"));

        assertThatThrownBy(() -> repostService.unrepost(18L, 1L)).isInstanceOf(IllegalStateException.class);

        assertThat(repostService.getContexts(List.of(18L), 1L).get(0).repostedByMe()).isTrue();
        assertThat(repostService.listReposts(1L, null, 20).videoIds()).containsExactly(18L);
    }

    @Test
    void repost_pastTheLimit_isRefusedAndLeavesTheCounterAlone() {
        redisTemplate.opsForValue().set("interaction:repost-rate:1:70", "60");

        assertThatThrownBy(() -> repostService.repost(70L, 1L))
                .isInstanceOf(RepostRateLimitedException.class);
        assertThat(counterCacheService.getCounts(70L).repostCount()).isZero();
    }
}
