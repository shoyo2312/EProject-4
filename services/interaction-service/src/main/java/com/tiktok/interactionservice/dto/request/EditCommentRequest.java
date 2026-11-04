package com.tiktok.interactionservice.dto.request;

import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import jakarta.validation.constraints.NotBlank;

/**
 * The new text of a comment — sanitized before validation, exactly as {@link AddCommentRequest}
 * is. A sticker cannot be changed by an edit: it stays as it was posted.
 */
public record EditCommentRequest(@NotBlank @MaxCodePoints(150) String content) {

    public EditCommentRequest {
        content = TextSanitizer.sanitize(content);
    }
}
