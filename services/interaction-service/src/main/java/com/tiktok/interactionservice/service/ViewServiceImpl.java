package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.request.ViewRequest;
import com.tiktok.interactionservice.dto.request.WatchRequest;
import com.tiktok.interactionservice.dto.response.ViewResponse;
import com.tiktok.interactionservice.dto.response.WatchResponse;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.ViewRateLimitedException;
import com.tiktok.interactionservice.exception.WatchRateLimitedException;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ViewServiceImpl implements ViewService {

    /**
     * How long one playId is remembered. Not a dedup window in the "one view per viewer per day"
     * sense — replaying the video produces a new playId and counts again, which is what makes the
     * number behave the way a viewer expects. This only has to outlive the retries of a single
     * request, so it is measured against the longest video the platform will play, not against
     * how often somebody may watch.
     */
    private static final Duration PLAY_TTL = Duration.ofMinutes(15);

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
     * The shortest playable video, and the floor a client-reported duration is held to when
     * video-service cannot confirm the real one. It is the sanity check the ceiling never was:
     * {@code {"watchedMs": 1, "durationMs": 1}} passes @Positive, survives the clamp, and lands a
     * ratio of 1.0 — a full completion, at whatever rate the rate limiter allows. Raising the
     * denominator to this floor instead of rejecting the report keeps the watch counted while
     * making the ratio what such a claim deserves.
     */
    private static final long MIN_DURATION_MS = Duration.ofSeconds(3).toMillis();

    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionRateLimiter rateLimiter;
    private final VideoOwnershipClient videoOwnershipClient;
    private final StringRedisTemplate redisTemplate;

    @Override
    public ViewResponse recordView(Long videoId, Long currentUserId, ViewRequest request) {
        // Read before the write, and add the delta here rather than reading again afterwards. A
        // Cassandra counter read is not guaranteed to see the increment that just happened, and
        // the read after an invalidate is the one that repopulates the cache — so a stale value
        // would not merely be returned once, it would be pinned there for the cache's whole TTL.
        long viewCount = counterCacheService.getCounts(videoId).viewCount();

        // Before the claim, not after: a request refused past the claim would have burned its
        // playId on the way out, so the client's retry would be told the view was already counted
        // when nothing counted it.
        rateLimiter.require("view-rate", videoId, currentUserId, ViewRateLimitedException::new);

        String playKey = playKey(videoId, currentUserId, request.playId());
        boolean counted = claimPlay(playKey);

        if (counted) {
            // A claimed play that fails past this point must not stay claimed: the counter never
            // moved, so the client's retry (or a fresh replay reusing the same playId within the
            // TTL) needs the key gone to have another chance, rather than being told `counted:
            // false` for a view that was never actually counted.
            boolean countered = false;
            try {
                videoCountersRepository.incrementViewCount(videoId, 1);
                countered = true;
                counterCacheService.invalidate(videoId);
                eventPublisher.publishView(videoId, currentUserId);
            } catch (RuntimeException ex) {
                // The counter goes back before the claim does. Releasing the claim while the
                // increment stands is what turned a failed publish into a permanent over-count:
                // the retry claimed a fresh playId slot and added a second view for one play.
                if (countered) {
                    undoView(videoId);
                }
                releasePlay(playKey);
                throw ex;
            }
            viewCount++;
        }

        return new ViewResponse(videoId, counted, viewCount);
    }

    @Override
    public WatchResponse recordWatch(Long videoId, Long currentUserId, WatchRequest request) {
        // The denominator comes from video-service when it knows it, and only falls back to the
        // client's own claim when it does not. Both numbers arriving from the client is not merely
        // a wrong row, it is the most attractive row in the training set — the label is a ratio,
        // and the highest ratios are what a ranker learns hardest from. The fallback is clamped
        // into [MIN, MAX]: past the ceiling the report describes a video that cannot exist here,
        // and under the floor it describes one nobody could have watched.
        long durationMs = resolveDurationMs(videoId, request.durationMs());
        long watchedMs = Math.min(request.watchedMs(), durationMs);
        boolean completed = watchedMs >= durationMs * COMPLETION_RATIO;

        rateLimiter.require("watch-rate", videoId, currentUserId, WatchRateLimitedException::new);
        eventPublisher.publishWatch(videoId, currentUserId, watchedMs, durationMs, completed);

        return new WatchResponse(videoId, watchedMs, completed);
    }

    /**
     * Prefers the duration the transcode probed out of the file over the one the player reports.
     * A confirmed duration needs no floor — a genuinely two-second video is allowed to be
     * completed in two seconds — and it is the fallback, not the real number, that a fabricated
     * report can move.
     *
     * <p>Fails open to the clamped client value: a video still transcoding has no probed duration
     * at all, and an unreachable video-service must not stop watches being recorded.
     */
    private long resolveDurationMs(Long videoId, long reportedMs) {
        long probedMs = videoOwnershipClient.durationMs(videoId);
        if (probedMs > 0) {
            return Math.min(probedMs, MAX_DURATION_MS);
        }
        return Math.clamp(reportedMs, MIN_DURATION_MS, MAX_DURATION_MS);
    }

    /**
     * Claims one playback, so the counter moves once per play no matter how many times the client
     * sends the request. The viewer is part of the key as well as the playId: the playId is
     * untrusted, and one client reusing another's value must not be able to swallow their view.
     *
     * <p>Fails open — a Redis outage counts every delivery rather than refusing every view. The
     * failure mode of the other choice is a video that visibly stops accumulating views; this one
     * over-counts retries for the duration of the outage and then corrects itself. That takes the
     * catch: an unreachable Redis throws out of the template rather than answering null, so
     * without it the outage would be a 500 on every play instead.
     */
    private boolean claimPlay(String playKey) {
        try {
            return !Boolean.FALSE.equals(redisTemplate.opsForValue().setIfAbsent(playKey, "1", PLAY_TTL));
        } catch (RuntimeException e) {
            log.warn("Could not claim {}, counting the view anyway: {}", playKey, e.getMessage());
            return true;
        }
    }

    /**
     * Takes back an increment whose request did not finish. Swallowed like {@link #releasePlay}:
     * the failure already on its way out is the one worth seeing, and a compensation that fails
     * leaves the same inconsistency as not trying — but logged.
     */
    private void undoView(Long videoId) {
        try {
            videoCountersRepository.incrementViewCount(videoId, -1);
        } catch (RuntimeException e) {
            log.error("Could not take back the view counted for video {}; it is now over by one",
                    videoId, e);
        }
    }

    /**
     * Best effort, and swallowed on purpose: this runs while an exception is already on its way
     * out, and the caller needs to see <em>that</em> failure. A release that fails leaves the play
     * claimed for its TTL, which costs one uncounted retry — the same outcome as before this
     * compensation existed, and a far smaller one than replacing the real cause with a Redis error.
     */
    private void releasePlay(String playKey) {
        try {
            redisTemplate.delete(playKey);
        } catch (RuntimeException e) {
            log.warn("Could not release the claim on {}: {}", playKey, e.getMessage());
        }
    }

    private String playKey(Long videoId, Long currentUserId, String playId) {
        return "interaction:play:%d:%d:%s".formatted(currentUserId, videoId, playId);
    }

}
