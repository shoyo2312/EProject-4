package com.tiktok.storyservice.dto.response;

import com.tiktok.storyservice.entity.StoryReaction;

import java.time.Instant;

public record StoryViewerResponse(
        Long viewerId,
        Instant viewedAt,
        StoryReaction reaction
) {
}
