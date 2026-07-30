package com.tiktok.interactionservice.dto.response;

public record LikeStatusResponse(
        Long videoId,
        boolean liked,
        long likeCount
) {
}
