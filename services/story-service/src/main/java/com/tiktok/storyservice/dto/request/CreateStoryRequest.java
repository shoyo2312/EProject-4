package com.tiktok.storyservice.dto.request;

import com.tiktok.storyservice.entity.StoryVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateStoryRequest(
        /** The {@code fileUrl} from {@code POST /stories/upload-url}. */
        @NotBlank String mediaUrl,
        @Size(max = 300) String caption,
        @NotNull StoryVisibility visibility
) {
}
