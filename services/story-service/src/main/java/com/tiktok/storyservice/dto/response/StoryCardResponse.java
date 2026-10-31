package com.tiktok.storyservice.dto.response;

import com.tiktok.storyservice.entity.StoryMediaType;

/**
 * What a story is to one viewer, for another service that must decide whether that viewer may see
 * it (chat-service, drawing a story reply). A story the viewer may not see has no card at all.
 *
 * @param mediaUrl a presigned GET for this viewer, good for an hour; never stored by the caller
 */
public record StoryCardResponse(String id, Long userId, StoryMediaType mediaType, String mediaUrl) {
}
