package com.tiktok.interactionservice.service;

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

    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionRateLimiter rateLimiter;
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
            try {
                videoCountersRepository.incrementViewCount(videoId, 1);
                counterCacheService.invalidate(videoId);
                eventPublisher.publishView(videoId, currentUserId);
            } catch (RuntimeException ex) {
                releasePlay(playKey);
                throw ex;
            }
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

        rateLimiter.require("watch-rate", videoId, currentUserId, WatchRateLimitedException::new);
        eventPublisher.publishWatch(videoId, currentUserId, watchedMs, durationMs, completed);

        return new WatchResponse(videoId, watchedMs, completed);
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
