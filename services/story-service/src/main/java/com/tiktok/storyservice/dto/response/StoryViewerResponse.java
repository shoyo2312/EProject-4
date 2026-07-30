package com.tiktok.storyservice.dto.response;

import java.time.Instant;

public record StoryViewerResponse(
        Long viewerId,
        Instant viewedAt
) {
}
