package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;

import java.time.Instant;
import java.util.List;

/**
 * The write side: everything Kafka listeners call to fold an event into Redis, plus the
 * scheduled rebuild that turns the hourly engagement buckets into a decayed ranking.
 */
public interface RecommendationService {

    /**
     * Remembers a video's tags without putting it anywhere the feed reads from. The publication
     * event fires while the video is still PROCESSING — media-worker needs that very event to
     * start transcoding — so indexing on it hands the feed ids that no viewer can play: the
     * batch hydration drops them again, silently shortening the page, and the served-set marks
     * them suppressed for the next half hour, which is exactly when they become playable.
     */
    void recordVideoUploaded(String videoId, Long ownerId, String visibility, List<String> tags);

    /**
     * PUBLIC puts the video back into candidate generation; FRIENDS or PRIVATE takes it out. The
     * public feed is for everyone, so a video only some viewers may see has no place in it.
     */
    void recordVisibilityChanged(String videoId, String visibility);

    /** Takes a video out of candidate generation while keeping what a restore needs to undo it. */
    void recordTakenDown(String videoId);

    /**
     * The admin restore, which means what it means in video-service's {@code Video.markRestored}:
     * on a taken-down video it lifts the takedown and puts the video back where it was, and on a
     * held one it is the approval that puts it into the feed.
     *
     * @param restoredAt when the admin decided, which a verdict read after this is held against
     */
    void recordRestored(String videoId, Instant restoredAt);

    /**
     * Keeps a video moderation did not approve out of the feed, in a state an admin approval can
     * lift. Unlike {@link #recordVideoDeleted} the stashed tags and owner stay, because the
     * approval has nothing else to index the video from.
     *
     * @param checkedAt when the classifier produced the verdict
     */
    void recordHeld(String videoId, Instant checkedAt);

    /**
     * Keeps a video whose transcode failed out of the feed without forgetting it. A failure is not
     * final: video-service leaves FAILED open for a redelivered publication to rescue, and the
     * rescue comes in through {@link #recordVideoReady} like any first success. So unlike
     * {@link #recordVideoDeleted} this leaves no tombstone and keeps the stashed tags, owner and
     * private flag.
     */
    void recordTranscodeFailed(String videoId);

    /**
     * Puts a video into everything candidate generation reads — trending, the per-tag indexes,
     * the publish-time set — using the tags {@link #recordVideoUploaded} stashed. Driven by the
     * transcode result, so nothing enters the feed before it can be played.
     */
    void recordVideoReady(String videoId);

    /**
     * Removes every trace of a video from the ranking side. Without it a deleted video keeps
     * being handed out: it stays in the trending ranking, and in the per-tag indexes candidate
     * generation reads from, until it happens to fall out by trimming.
     */
    void recordVideoDeleted(String videoId);

    void recordLike(String videoId, boolean liked);

    /** Scores only the first share by {@code userId} of this video within the trending window. */
    void recordShare(String videoId, Long userId);

    /**
     * @param created true for a new comment, false when one was removed — the engagement a
     *                comment earned is taken back when it stops existing, same as an unlike.
     */
    void recordComment(String videoId, boolean created);

    /**
     * Folds one watch session in: it is engagement, it is a quality datapoint for the video, it
     * teaches the viewer's tag profile, and it marks the video as seen so the feed stops
     * offering it.
     */
    void recordWatch(String videoId, Long userId, long watchedMs, long durationMs, boolean completed);

    List<TrendingVideoResponse> getTrending(int limit);
}
