package com.tiktok.recommendationservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.recommendationservice.dto.response.FeedItemResponse;
import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;
import com.tiktok.recommendationservice.service.FeedService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private static final int MAX_LIMIT = 100;

    /**
     * A feed page is ids and nothing else, so every one of them costs the client a hydration
     * request to video-service's /batch — which truncates at its own max-page-size (50) without
     * saying so. Asking for more here does not fail, it just returns ids whose videos silently
     * never arrive. Raise this only together with that setting.
     */
    private static final int MAX_FEED_LIMIT = 50;

    private final RecommendationService recommendationService;
    private final FeedService feedService;

    @GetMapping("/trending")
    public ApiResponse<List<TrendingVideoResponse>> getTrending(
            @RequestParam(defaultValue = "20") int limit) {
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ApiResponse.success(recommendationService.getTrending(cappedLimit));
    }

    /**
     * The personalized ranking for the signed-in viewer. Returns ids, not videos: this service
     * has no read path into video-service's data and the client already holds most of what it
     * would send back.
     */
    @GetMapping("/feed")
    public ApiResponse<List<FeedItemResponse>> getFeed(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(defaultValue = "20") int limit) {
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_FEED_LIMIT);
        return ApiResponse.success(feedService.getFeed(currentUserId, cappedLimit));
    }
}
