package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.ShareResponse;
import com.tiktok.interactionservice.exception.ShareRateLimitedException;
import com.tiktok.interactionservice.repository.ShareByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShareServiceImplTest extends AbstractInteractionServiceIT {

    @Autowired
    private ShareService shareService;

    @Autowired
    private ShareByVideoRepository shareByVideoRepository;

    @Autowired
    private VideoCountersRepository videoCountersRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        shareByVideoRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void share_incrementsShareCount() {
        ShareResponse response = shareService.share(30L, 1L);

        assertThat(response.shareCount()).isEqualTo(1L);
        assertThat(response.videoId()).isEqualTo(30L);
    }

    @Test
    void share_calledRepeatedly_eachCallCountsSeparately() {
        shareService.share(31L, 1L);
        shareService.share(31L, 1L);
        ShareResponse third = shareService.share(31L, 1L);

        assertThat(third.shareCount()).isEqualTo(3L);
    }

    /**
     * Nothing about a share is idempotent — every call is a new row and a new +1 — so the limit is
     * all that stands between this counter and a loop, and a share is the heaviest signal trending
     * has.
     */
    @Test
    void share_pastTheHourlyLimit_isRefused() {
        for (int i = 0; i < 60; i++) {
            shareService.share(32L, 1L);
        }

        assertThatThrownBy(() -> shareService.share(32L, 1L))
                .isInstanceOf(ShareRateLimitedException.class);
        assertThat(shareService.share(32L, 2L).shareCount()).isEqualTo(61L);
    }

    /**
     * The count comes back read-before-write plus the delta, not re-read afterwards: a Cassandra
     * counter read is not guaranteed to see its own increment, and the read that follows the cache
     * invalidate is the one that repopulates the cache — a stale value there would be pinned for
     * the whole TTL rather than merely returned once.
     */
    @Test
    void share_returnsTheCountIncludingThisShare() {
        shareService.share(33L, 1L);

        assertThat(shareService.share(33L, 2L).shareCount()).isEqualTo(2L);
    }
}
