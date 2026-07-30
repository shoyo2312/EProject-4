package com.tiktok.storyservice.dto.response;

import com.tiktok.storyservice.entity.StoryMediaType;

import java.time.Instant;

public record StoryResponse(
        String id,
        Long userId,
        String mediaUrl,
        StoryMediaType mediaType,
        String caption,
        long viewCount,
        Instant createdAt,
        Instant expiresAt
) {
}
