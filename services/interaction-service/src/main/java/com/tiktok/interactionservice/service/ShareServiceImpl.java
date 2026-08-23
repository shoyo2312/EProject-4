package com.tiktok.interactionservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.interactionservice.dto.response.ShareResponse;
import com.tiktok.interactionservice.entity.ShareByVideo;
import com.tiktok.interactionservice.entity.ShareByVideoKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.ShareRateLimitedException;
import com.tiktok.interactionservice.repository.ShareByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShareServiceImpl implements ShareService {

    private final ShareByVideoRepository shareByVideoRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionRateLimiter rateLimiter;

    @Override
    public ShareResponse share(Long videoId, Long currentUserId) {
        // Read before the write, and add the delta here rather than reading again afterwards. A
        // Cassandra counter read is not guaranteed to see the increment that just happened, and
        // the read after an invalidate is the one that repopulates the cache — so a stale value
        // would not merely be returned once, it would be pinned there for the cache's whole TTL.
        long shareCount = counterCacheService.getCounts(videoId).shareCount();

        // Unlike a like, a share has nothing about it that is idempotent: every call is a new row
        // and a new +1 by design, since sharing the same video twice really is two shares. The
        // limit is therefore the only thing standing between this counter and a loop — and a
        // share is the highest-weighted signal trending has, so that loop buys more ranking per
        // request than any other endpoint here.
        rateLimiter.require("share-rate", videoId, currentUserId, ShareRateLimitedException::new);

        Long shareId = SnowflakeIdGenerator.nextId();
        ShareByVideoKey key = ShareByVideoKey.builder().videoId(videoId).shareId(shareId).build();

        shareByVideoRepository.save(ShareByVideo.builder()
                .key(key)
                .userId(currentUserId)
                .createdAt(Instant.now())
                .build());

        // A stored share whose counter never moved is a share nothing will ever count: no later
        // event recomputes this number, and the rows themselves are not what any read path totals.
        // Removing the row again makes the client's retry a clean first attempt rather than a
        // second row against a counter that is still short by one.
        boolean countered = false;
        try {
            videoCountersRepository.incrementShareCount(videoId, 1);
            countered = true;
            counterCacheService.invalidate(videoId);
            eventPublisher.publishShare(shareId, videoId, currentUserId);
        } catch (RuntimeException ex) {
            // The counter is taken back too, not only the row. Removing the row while the
            // increment stands leaves the count ahead of the shares that exist, and the client's
            // retry — which this compensation exists to make clean — adds a second one.
            if (countered) {
                undoCounter(videoId);
            }
            shareByVideoRepository.deleteById(key);
            throw ex;
        }

        return new ShareResponse(shareId, videoId, shareCount + 1);
    }

    /**
     * Swallowed on purpose: an exception is already on its way to the caller and it is the one
     * worth seeing. A compensation that fails leaves the inconsistency it was meant to remove,
     * which is no worse than not trying, and the log is what says so.
     */
    private void undoCounter(Long videoId) {
        try {
            videoCountersRepository.incrementShareCount(videoId, -1);
        } catch (RuntimeException e) {
            log.error("Could not take back the share counted for video {}; it is now over by one",
                    videoId, e);
        }
    }
}
