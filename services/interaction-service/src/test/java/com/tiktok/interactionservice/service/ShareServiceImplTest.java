package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.ShareResponse;
import com.tiktok.interactionservice.repository.ShareByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

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
}
