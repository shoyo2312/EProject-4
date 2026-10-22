package com.tiktok.storyservice.dto.response;

/**
 * One avatar's ring. Only accounts with a story to show the caller get one.
 *
 * @param hasUnseen whether the caller has a story here they have not opened yet; always true for a
 *                  guest. For the caller's own account, whether one is ACTIVE — a story still in
 *                  review or held is a grey ring only they see
 */
public record StoryRingResponse(Long userId, boolean hasUnseen) {
}
