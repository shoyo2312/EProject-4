package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;

import java.util.List;

/**
 * The write side: everything Kafka listeners call to fold an event into Redis, plus the
 * scheduled rebuild that turns the hourly engagement buckets into a decayed ranking.
 */
public interface RecommendationService {

    void recordVideoPublished(String videoId, List<String> tags);

    void recordLike(String videoId, boolean liked);

    void recordShare(String videoId);

    void recordComment(String videoId);

    /**
     * Folds one watch session in: it is engagement, it is a quality datapoint for the video, it
     * teaches the viewer's tag profile, and it marks the video as seen so the feed stops
     * offering it.
     */
    void recordWatch(String videoId, Long userId, long watchedMs, long durationMs, boolean completed);

    List<TrendingVideoResponse> getTrending(int limit);
}
