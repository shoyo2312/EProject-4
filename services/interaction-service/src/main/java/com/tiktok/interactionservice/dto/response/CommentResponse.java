package com.tiktok.interactionservice.dto.response;

import java.time.Instant;

public record CommentResponse(
        Long commentId,
        Long videoId,
        Long userId,
        String content,
        Instant createdAt
) {
}
