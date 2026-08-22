package com.tiktok.interactionservice.service;

import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException;
import com.tiktok.interactionservice.dto.request.WatchRequest;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.dto.response.WatchResponse;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.InteractionConflictException;
import com.tiktok.interactionservice.exception.WatchRateLimitedException;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import com.tiktok.interactionservice.repository.ViewByVideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    /**
     * What counts as watching to the end. Not 1.0: players stop reporting a frame or two early,
     * and a viewer who sat through 95% did not abandon the video — treating them as a negative
     * would teach the ranker that finishing is rare when it is not.
     */
    private static final double COMPLETION_RATIO = 0.9;

    /**
     * The longest video this platform will play. durationMs is the client's number too, so
     * clamping watchedMs against it alone leaves the ratio entirely in the client's hands: send
     * the two equal and every session is a completion. This ceiling is what makes the pair mean
     * something — past it the report describes a video that cannot exist here.
     */
    private static final long MAX_DURATION_MS = Duration.ofMinutes(10).toMillis();

    /**
     * How many sessions one viewer may report for one video per window. High enough that a real
     * viewer replaying a video all evening is never refused, low enough that a script cannot
     * write thousands of labelled rows about a video it wants promoted.
     */
    private static final long MAX_SESSIONS_PER_WINDOW = 60;

    private static final Duration SESSION_LIMIT_WINDOW = Duration.ofHours(1);

    private final ViewByVideoRepository viewByVideoRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;

    @Override
    public ViewResponse recordView(Long videoId, Long currentUserId) {
        // Read before the write, and add the delta here rather than reading again afterwards. A
        // Cassandra counter read is not guaranteed to see the increment that just happened, and
        // the read after an invalidate is the one that repopulates the cache — so a stale value
        // would not merely be returned once, it would be pinned there for the cache's whole TTL.
        long viewCount = counterCacheService.getCounts(videoId).viewCount();

        boolean counted = executeLwtWithRetry(() -> viewByVideoRepository.insertIfNotExists(
                videoId, currentUserId, Instant.now(), DEDUP_WINDOW_SECONDS));

        if (counted) {
            videoCountersRepository.incrementViewCount(videoId, 1);
            counterCacheService.invalidate(videoId);
            eventPublisher.publishView(videoId, currentUserId);
            viewCount++;
        }

        return new ViewResponse(videoId, counted, viewCount);
    }

    @Override
    public WatchResponse recordWatch(Long videoId, Long currentUserId, WatchRequest request) {
        // Clamped because both numbers come from the client and nothing stops them claiming an
        // hour on a fifteen-second video. Left unclamped that is not merely a wrong row, it is the
        // most attractive row in the training set — the label is a ratio, and the highest ratios
        // are what a ranker learns hardest from.
        long durationMs = Math.min(request.durationMs(), MAX_DURATION_MS);
        long watchedMs = Math.min(request.watchedMs(), durationMs);
        boolean completed = watchedMs >= durationMs * COMPLETION_RATIO;

        requireWithinSessionLimit(videoId, currentUserId);
        eventPublisher.publishWatch(videoId, currentUserId, watchedMs, durationMs, completed);

        return new WatchResponse(videoId, watchedMs, completed);
    }

    /**
     * Clamping bounds one row; this bounds how many rows exist. A watch event is an unauthenticated
     * vote on where a video ranks, replayable as fast as HTTP allows, and the ranker weights volume.
     * Refusing with 429 rather than dropping the event silently keeps an honest client — one that
     * genuinely replayed a video sixty times in an hour — able to tell that it was refused.
     *
     * <p>ponytail: a fixed counter per (viewer, video, hour), so a burst at the boundary can cross
     * two windows. A sliding window is the upgrade if that ever matters; it does not here, where
     * the point is the order of magnitude and not the exact number.
     */
    private void requireWithinSessionLimit(Long videoId, Long currentUserId) {
        String key = "interaction:watch-rate:%d:%d".formatted(currentUserId, videoId);
        Long sessions = redisTemplate.opsForValue().increment(key);

        if (sessions == null) {
            return;
        }
        if (sessions == 1L) {
            redisTemplate.expire(key, SESSION_LIMIT_WINDOW);
        }
        if (sessions > MAX_SESSIONS_PER_WINDOW) {
            throw new WatchRateLimitedException();
        }
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
