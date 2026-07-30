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
        }

        return buildStatus(videoId, true);
    }

    @Override
    public LikeStatusResponse unlike(Long videoId, Long currentUserId) {
        boolean wasLiked = executeLwtWithRetry(
                () -> likeByVideoRepository.deleteIfExists(videoId, currentUserId));

        if (wasLiked) {
            videoCountersRepository.incrementLikeCount(videoId, -1);
            likeByUserRepository.deleteById(LikeByUserKey.builder().userId(currentUserId).videoId(videoId).build());
            counterCacheService.invalidate(videoId);
            eventPublisher.publishLike(videoId, currentUserId, false);
        }

        return buildStatus(videoId, false);
    }

    @Override
    public LikeStatusResponse getStatus(Long videoId, Long currentUserId) {
        boolean liked = currentUserId != null
                && likeByVideoRepository.existsById(LikeByVideoKey.builder().videoId(videoId).userId(currentUserId).build());
        long likeCount = counterCacheService.getCounts(videoId).likeCount();
        return new LikeStatusResponse(videoId, liked, likeCount);
    }

    private LikeStatusResponse buildStatus(Long videoId, boolean liked) {
        long likeCount = counterCacheService.getCounts(videoId).likeCount();
        return new LikeStatusResponse(videoId, liked, likeCount);
    }

    /**
     * A WriteTimeoutException on a lightweight transaction leaves the true outcome
     * indeterminate. Retrying the LWT itself is safe (IF NOT EXISTS / IF EXISTS is
     * idempotent by nature); a bare counter increment must never be blindly retried.
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
        throw new InteractionConflictException("Like/unlike could not be confirmed after retries, please try again");
    }
}
