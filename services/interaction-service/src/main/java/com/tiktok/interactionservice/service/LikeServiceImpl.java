package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.entity.LikeByUser;
import com.tiktok.interactionservice.entity.LikeByUserKey;
import com.tiktok.interactionservice.entity.LikeByVideoKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import com.tiktok.interactionservice.repository.LikeByUserRepository;
import com.tiktok.interactionservice.repository.LikeByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.BooleanSupplier;

@Service
@RequiredArgsConstructor
public class LikeServiceImpl implements LikeService {

    private static final int MAX_LWT_RETRIES = 2;

    private final LikeByVideoRepository likeByVideoRepository;
    private final LikeByUserRepository likeByUserRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;

    @Override
    public LikeStatusResponse like(Long videoId, Long currentUserId) {
        // Read before the write, and add the delta here rather than reading again afterwards. A
        // Cassandra counter read is not guaranteed to see the increment that just happened, and
        // the read after an invalidate is the one that repopulates the cache — so a stale value
        // would not merely be returned once, it would be pinned there for the cache's whole TTL.
        long likeCount = counterCacheService.getCounts(videoId).likeCount();

        boolean newlyLiked = executeLwtWithRetry(
                () -> likeByVideoRepository.insertIfNotExists(videoId, currentUserId, Instant.now()));

        if (newlyLiked) {
            videoCountersRepository.incrementLikeCount(videoId, 1);
            likeByUserRepository.save(LikeByUser.builder()
                    .key(LikeByUserKey.builder().userId(currentUserId).videoId(videoId).build())
                    .createdAt(Instant.now())
                    .build());
            counterCacheService.invalidate(videoId);
            eventPublisher.publishLike(videoId, currentUserId, true);
            likeCount++;
        }

        return new LikeStatusResponse(videoId, true, likeCount);
    }

    @Override
    public LikeStatusResponse unlike(Long videoId, Long currentUserId) {
        long likeCount = counterCacheService.getCounts(videoId).likeCount();

        boolean wasLiked = executeLwtWithRetry(
                () -> likeByVideoRepository.deleteIfExists(videoId, currentUserId));

        if (wasLiked) {
            videoCountersRepository.incrementLikeCount(videoId, -1);
            likeByUserRepository.deleteById(LikeByUserKey.builder().userId(currentUserId).videoId(videoId).build());
            counterCacheService.invalidate(videoId);
            eventPublisher.publishLike(videoId, currentUserId, false);
            likeCount--;
        }

        return new LikeStatusResponse(videoId, false, Math.max(likeCount, 0));
    }

    @Override
    public LikeStatusResponse getStatus(Long videoId, Long currentUserId) {
        boolean liked = currentUserId != null
                && likeByVideoRepository.existsById(LikeByVideoKey.builder().videoId(videoId).userId(currentUserId).build());
        long likeCount = counterCacheService.getCounts(videoId).likeCount();
        return new LikeStatusResponse(videoId, liked, likeCount);
    }

    /**
     * A WriteTimeoutException on a lightweight transaction leaves the true outcome
     * indeterminate. Retrying the LWT itself is safe (IF NOT EXISTS / IF EXISTS is
     * idempotent by nature); a bare counter increment must never be blindly retried.
     *
     * <p>The last timeout is attached as the cause: what the coordinator said about the failed
     * write is the only thing that explains a conflict nobody can reproduce afterwards.
     */
    private boolean executeLwtWithRetry(BooleanSupplier lwtOperation) {
        WriteTimeoutException lastError = null;
        for (int attempt = 0; attempt <= MAX_LWT_RETRIES; attempt++) {
            try {
                return lwtOperation.getAsBoolean();
            } catch (WriteTimeoutException e) {
                lastError = e;
            }
        }
        InteractionConflictException failure = new InteractionConflictException(
                "Like/unlike could not be confirmed after retries, please try again");
        failure.initCause(lastError);
        throw failure;
    }
}
