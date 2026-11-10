package com.tiktok.storyservice.dto.response;

import java.util.List;

/**
 * One owner's bubble in the tray.
 *
 * @param stories   oldest first, the order they play in
 * @param hasUnseen whether any of them the viewer has not opened yet; always false for the viewer's own
 */
public record StoryGroupResponse(Long userId, List<StoryResponse> stories, boolean hasUnseen) {
}
