package com.tiktok.storyservice.dto.request;

import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import com.tiktok.storyservice.entity.StoryVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The caption is sanitized before validation, as a video's title is: invisible and bidi characters
 * go, so what the rules screen is what a viewer reads — a right-to-left override would otherwise
 * show a word the rules never saw. See {@link TextSanitizer}.
 */
public record CreateStoryRequest(
        /** The {@code fileUrl} from {@code POST /stories/upload-url}. */
        @NotBlank String mediaUrl,
        @MaxCodePoints(300) String caption,
        @NotNull StoryVisibility visibility
) {

    public CreateStoryRequest {
        caption = TextSanitizer.sanitize(caption);
    }
}
