package com.tiktok.interactionservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.interactionservice.dto.response.ShareResponse;
import com.tiktok.interactionservice.entity.ShareByVideo;
import com.tiktok.interactionservice.entity.ShareByVideoKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.repository.ShareByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private final ShareByVideoRepository shareByVideoRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;

    @Override
    public ShareResponse share(Long videoId, Long currentUserId) {
        Long shareId = SnowflakeIdGenerator.nextId();

        shareByVideoRepository.save(ShareByVideo.builder()
                .key(ShareByVideoKey.builder().videoId(videoId).shareId(shareId).build())
                .userId(currentUserId)
                .createdAt(Instant.now())
                .build());

        videoCountersRepository.incrementShareCount(videoId, 1);
        counterCacheService.invalidate(videoId);
        eventPublisher.publishShare(shareId, videoId, currentUserId);

        long shareCount = counterCacheService.getCounts(videoId).shareCount();
        return new ShareResponse(shareId, videoId, shareCount);
    }
}
