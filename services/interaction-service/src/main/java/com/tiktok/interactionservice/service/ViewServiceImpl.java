package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import com.tiktok.interactionservice.repository.ViewByVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class ViewServiceImpl implements ViewService {

    private static final int MAX_LWT_RETRIES = 2;

    /**
     * How long one viewer's view stands before the same viewer can count again. A day rather
     * than a per-session window: the number this feeds is a lifetime view count on a profile
     * grid, so a viewer who leaves a video looping should move it once, not once a minute.
     */
    private static final int DEDUP_WINDOW_SECONDS = (int) Duration.ofDays(1).toSeconds();

    private final ViewByVideoRepository viewByVideoRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;

    @Override
    public ViewResponse recordView(Long videoId, Long currentUserId) {
        boolean counted = executeLwtWithRetry(() -> viewByVideoRepository.insertIfNotExists(
                videoId, currentUserId, Instant.now(), DEDUP_WINDOW_SECONDS));

        if (counted) {
            videoCountersRepository.incrementViewCount(videoId, 1);
            counterCacheService.invalidate(videoId);
            eventPublisher.publishView(videoId, currentUserId);
        }

        return new ViewResponse(videoId, counted, counterCacheService.getCounts(videoId).viewCount());
    }

    /**
     * Same reasoning as {@code LikeServiceImpl}: a WriteTimeoutException leaves an LWT's outcome
     * unknown, and re-running an IF NOT EXISTS is harmless, whereas re-running the counter
     * increment that follows it would not be.
     */
    private boolean executeLwtWithRetry(java.util.function.BooleanSupplier lwtOperation) {
        for (int attempt = 0; attempt <= MAX_LWT_RETRIES; attempt++) {
            try {
                return lwtOperation.getAsBoolean();
            } catch (WriteTimeoutException ignored) {
                // Retried below; the last failure surfaces as the conflict thrown after the loop.
            }
        }
        throw new InteractionConflictException("View could not be confirmed after retries, please try again");
    }
}
