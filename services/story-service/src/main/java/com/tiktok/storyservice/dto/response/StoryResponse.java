package com.tiktok.storyservice.dto.response;

import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.entity.StoryReaction;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryVisibility;

import java.time.Instant;
import java.util.Map;

/**
 * @param mediaUrl   a presigned GET good for an hour — fetch the story again for a fresh one
 * @param status     the owner's view only; null for everyone else, who only ever sees ACTIVE stories
 * @param viewCount  the owner's view only
 * @param viewedByMe whether the requester has already opened it
 * @param myReaction the requester's reaction, null when none (and always for the owner)
 * @param reactionCounts the owner's view only; how many viewers reacted each way
 */
public record StoryResponse(
        String id,
        Long userId,
        String mediaUrl,
        StoryMediaType mediaType,
        String caption,
        StoryVisibility visibility,
        StoryStatus status,
        Integer durationSeconds,
        Long viewCount,
        boolean viewedByMe,
        StoryReaction myReaction,
        Map<StoryReaction, Long> reactionCounts,
        Instant createdAt,
        Instant expiresAt
) {

    /** What a viewer who is not the owner gets. */
    public StoryResponse forViewer() {
        return new StoryResponse(id, userId, mediaUrl, mediaType, caption, visibility, null, durationSeconds,
                null, viewedByMe, myReaction, null, createdAt, expiresAt);
    }
}
