package com.tiktok.videoservice.dto.response;

import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;

import java.time.Instant;

public record VideoResponse(
        String id,
        Long userId,
        String title,
        String description,
        String thumbnailUrl,
        String hlsUrl,
        Integer durationSeconds,
        VideoStatus status,
        VideoVisibility visibility,
        long viewCount,
        long likeCount,
        long commentCount,
        Instant createdAt
) {
}
