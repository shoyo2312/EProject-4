package com.tiktok.interactionservice.dto.response;

public record ShareResponse(
        Long shareId,
        Long videoId,
        long shareCount
) {
}
