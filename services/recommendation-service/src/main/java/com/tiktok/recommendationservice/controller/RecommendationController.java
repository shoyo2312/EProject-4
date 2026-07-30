package com.tiktok.recommendationservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
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

    private final RecommendationService recommendationService;

    @GetMapping("/trending")
    public ApiResponse<List<TrendingVideoResponse>> getTrending(
            @RequestParam(defaultValue = "20") int limit) {
        int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ApiResponse.success(recommendationService.getTrending(cappedLimit));
    }
}
