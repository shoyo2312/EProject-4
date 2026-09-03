package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;

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
    void recordVideoUploaded(String videoId, List<String> tags);

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

    void recordShare(String videoId);

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
