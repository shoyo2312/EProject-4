package com.tiktok.chatservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A story as one viewer may see it right now. {@code state} is AVAILABLE, UNAVAILABLE (gone, expired,
 * or the viewer may no longer watch it) or UNKNOWN (story-service did not answer). Only AVAILABLE
 * carries an owner and a media URL, and that URL is a presigned GET for this viewer, never stored.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StoryCard(String storyId, String state, Long ownerId, String mediaType, String mediaUrl) {
    public static StoryCard unavailable(String id) {
        return new StoryCard(id, "UNAVAILABLE", null, null, null);
    }

    public static StoryCard unknown(String id) {
        return new StoryCard(id, "UNKNOWN", null, null, null);
    }
}
