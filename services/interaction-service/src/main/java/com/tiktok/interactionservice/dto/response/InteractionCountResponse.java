package com.tiktok.interactionservice.dto.response;

public record InteractionCountResponse(
        Long videoId,
        long likeCount,
        long commentCount,
        long shareCount,
        long viewCount,
        long saveCount,
        long repostCount
) {
}
