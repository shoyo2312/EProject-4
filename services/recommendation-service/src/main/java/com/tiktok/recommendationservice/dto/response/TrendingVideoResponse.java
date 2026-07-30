package com.tiktok.recommendationservice.dto.response;

public record TrendingVideoResponse(
        String videoId,
        double score
) {
}
