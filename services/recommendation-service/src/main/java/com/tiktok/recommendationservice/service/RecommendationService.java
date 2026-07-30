package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;

import java.util.List;

public interface RecommendationService {

    void recordVideoPublished(String videoId);

    void recordLike(String videoId, boolean liked);

    void recordShare(String videoId);

    void recordComment(String videoId);

    List<TrendingVideoResponse> getTrending(int limit);
}
