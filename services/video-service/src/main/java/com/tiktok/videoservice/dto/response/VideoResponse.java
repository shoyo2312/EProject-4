package com.tiktok.videoservice.dto.response;

import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;

import java.time.Instant;
import java.util.List;

public record VideoResponse(
        String id,
        Long userId,
        String title,
        String description,
        String thumbnailUrl,
        /** Animated preview to play under the cursor; null means fall back to {@code thumbnailUrl}. */
        String previewUrl,
        String hlsUrl,
        Integer durationSeconds,
        VideoStatus status,
        VideoVisibility visibility,
        long viewCount,
        long likeCount,
        /** Null when {@code commentsDisabled} — a video with comments off exposes no comment total. */
        Long commentCount,
        boolean commentsDisabled,
        List<String> tags,
        Instant createdAt,
        /** Why the transcode failed; null unless {@code status == FAILED}. */
        String failureReason
) {
}
