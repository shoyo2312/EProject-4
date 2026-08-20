package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.Row;
import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import com.tiktok.interactionservice.repository.ViewByVideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ViewServiceImplTest extends AbstractInteractionServiceIT {

    @Autowired
    private ViewService viewService;

    @Autowired
    private ViewByVideoRepository viewByVideoRepository;

    @Autowired
    private VideoCountersRepository videoCountersRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CqlSession cqlSession;

    @BeforeEach
    void cleanUp() {
        viewByVideoRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void recordView_firstTime_countsIt() {
        ViewResponse response = viewService.recordView(20L, 1L);

        assertThat(response.counted()).isTrue();
        assertThat(response.viewCount()).isEqualTo(1L);
    }

    /**
     * The whole point of the dedup row: a viewer who replays the video, or a client retrying a
     * request whose response it never saw, must not move the counter a second time.
     */
    @Test
    void recordView_sameUserAgain_countsNothingMore() {
        viewService.recordView(21L, 1L);

        ViewResponse response = viewService.recordView(21L, 1L);

        assertThat(response.counted()).isFalse();
        assertThat(response.viewCount()).isEqualTo(1L);
    }

    /**
     * The dedup row's TTL is the window, and nothing else expires it. Without {@code USING TTL}
     * the insert would still deduplicate — permanently — so every viewer would count exactly once
     * in the video's lifetime and no other assertion here would notice.
     */
    @Test
    void recordView_writesTheDedupRowWithTheWindowAsItsTtl() {
        viewService.recordView(24L, 1L);

        Row row = cqlSession.execute(
                "SELECT TTL(viewed_at) AS ttl FROM views_by_video WHERE video_id = 24 AND user_id = 1").one();

        assertThat(row).isNotNull();
        assertThat(row.getInt("ttl")).isPositive().isLessThanOrEqualTo((int) Duration.ofDays(1).toSeconds());
    }

    @Test
    void recordView_isPerUser_countReflectsEachViewer() {
        viewService.recordView(22L, 1L);
        viewService.recordView(22L, 2L);

        assertThat(viewService.recordView(22L, 2L).viewCount()).isEqualTo(2L);
    }

    /**
     * The counter is cached in Redis and only invalidated on write. A stale hash written before
     * the view was counted would make the endpoint report the pre-view number back to the very
     * client that just caused the increment.
     */
    @Test
    void recordView_afterTheCounterWasCached_returnsTheFreshCount() {
        viewService.recordView(23L, 1L);

        assertThat(viewService.recordView(23L, 2L).viewCount()).isEqualTo(2L);
    }
}
