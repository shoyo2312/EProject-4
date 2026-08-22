package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.request.ViewRequest;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.exception.ViewRateLimitedException;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViewServiceImplTest extends AbstractInteractionServiceIT {

    @Autowired
    private ViewService viewService;

    @Autowired
    private VideoCountersRepository videoCountersRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    private static ViewRequest play() {
        return new ViewRequest(UUID.randomUUID().toString());
    }

    @Test
    void recordView_firstTime_countsIt() {
        ViewResponse response = viewService.recordView(20L, 1L, play());

        assertThat(response.counted()).isTrue();
        assertThat(response.viewCount()).isEqualTo(1L);
    }

    /**
     * The whole reason the count is per playback rather than per viewer: somebody who watches the
     * same video three times moved it three times, which is what everyone reading the number
     * assumes it means.
     */
    @Test
    void recordView_sameUserPlayingAgain_countsAgain() {
        viewService.recordView(21L, 1L, play());

        ViewResponse response = viewService.recordView(21L, 1L, play());

        assertThat(response.counted()).isTrue();
        assertThat(response.viewCount()).isEqualTo(2L);
    }

    /**
     * What the playId is for. A client retrying a request whose response it never saw sends the
     * same playId, and that is the one case that must not move the counter — otherwise a flaky
     * network reads as popularity.
     */
    @Test
    void recordView_samePlayIdTwice_countsOnce() {
        ViewRequest samePlay = play();
        viewService.recordView(25L, 1L, samePlay);

        ViewResponse response = viewService.recordView(25L, 1L, samePlay);

        assertThat(response.counted()).isFalse();
        assertThat(response.viewCount()).isEqualTo(1L);
    }

    /**
     * The playId is untrusted input. Keying the claim on it alone would let one viewer's value
     * swallow another viewer's view — and colliding on purpose costs an attacker nothing.
     */
    @Test
    void recordView_samePlayIdFromAnotherViewer_stillCounts() {
        ViewRequest samePlay = play();
        viewService.recordView(26L, 1L, samePlay);

        ViewResponse response = viewService.recordView(26L, 2L, samePlay);

        assertThat(response.counted()).isTrue();
        assertThat(response.viewCount()).isEqualTo(2L);
    }

    /**
     * With replays counting, the rate limit is the only thing left standing between the counter
     * and a script minting a fresh playId per request.
     */
    @Test
    void recordView_pastTheHourlyLimit_isRefused() {
        for (int i = 0; i < 60; i++) {
            viewService.recordView(27L, 1L, play());
        }

        assertThatThrownBy(() -> viewService.recordView(27L, 1L, play()))
                .isInstanceOf(ViewRateLimitedException.class);
        assertThat(viewService.recordView(27L, 2L, play()).viewCount()).isEqualTo(61L);
    }

    /**
     * The counter is cached in Redis and only invalidated on write. A stale hash written before
     * the view was counted would make the endpoint report the pre-view number back to the very
     * client that just caused the increment.
     */
    @Test
    void recordView_afterTheCounterWasCached_returnsTheFreshCount() {
        viewService.recordView(23L, 1L, play());

        assertThat(viewService.recordView(23L, 2L, play()).viewCount()).isEqualTo(2L);
    }
}
