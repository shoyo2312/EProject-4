package com.tiktok.searchservice.dto.response;

import java.time.Instant;
import java.util.List;

public record VideoSearchResponse(
        String id,
        Long userId,
        String title,
        String description,
        String thumbnailUrl,
        String status,
        List<String> tags,
        long viewCount,
        long likeCount,
        long commentCount,
        long shareCount,
        Instant createdAt
) {
}
