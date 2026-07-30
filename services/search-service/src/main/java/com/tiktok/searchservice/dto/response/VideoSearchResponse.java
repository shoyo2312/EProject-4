package com.tiktok.searchservice.dto.response;

import java.time.Instant;

public record VideoSearchResponse(
        String id,
        Long userId,
        String title,
        String description,
        String thumbnailUrl,
        String status,
        long viewCount,
        long likeCount,
        long commentCount,
        long shareCount,
        Instant createdAt
) {
}
