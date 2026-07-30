package com.tiktok.storyservice.dto.request;

import com.tiktok.storyservice.entity.StoryMediaType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateStoryRequest(
        @NotBlank String mediaUrl,
        @NotNull StoryMediaType mediaType,
        @Size(max = 300) String caption
) {
}
