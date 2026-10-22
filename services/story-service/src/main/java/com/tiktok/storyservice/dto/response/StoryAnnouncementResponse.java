package com.tiktok.storyservice.dto.response;

import com.tiktok.storyservice.entity.StoryVisibility;

/**
 * A story as notification-service re-checks it right before telling followers; the event carries
 * neither its current state nor its visibility.
 *
 * @param userId     the story's owner
 * @param visibility who may watch it now — the owner may have changed it since the event
 */
public record StoryAnnouncementResponse(Long userId, StoryVisibility visibility) {
}
