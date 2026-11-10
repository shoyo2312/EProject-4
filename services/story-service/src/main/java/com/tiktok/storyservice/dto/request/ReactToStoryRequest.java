package com.tiktok.storyservice.dto.request;

import com.tiktok.storyservice.entity.StoryReaction;
import jakarta.validation.constraints.NotNull;

public record ReactToStoryRequest(@NotNull StoryReaction reaction) {
}
