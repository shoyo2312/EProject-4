package com.tiktok.storyservice.dto.request;

import com.tiktok.storyservice.entity.StoryVisibility;
import jakarta.validation.constraints.NotNull;

public record UpdateStoryVisibilityRequest(@NotNull StoryVisibility visibility) {
}
