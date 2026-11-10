package com.tiktok.videoservice.dto.request;

import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code PATCH /api/v1/videos/{videoId}/caption}. Same limits and sanitising as
 * {@link CreateVideoRequest}, so an edit can store nothing a publish could not.
 */
public record UpdateCaptionRequest(
        @NotBlank @MaxCodePoints(150) String title,
        @MaxCodePoints(2000) String description
) {
    public UpdateCaptionRequest {
        title = TextSanitizer.sanitizeLine(title);
        description = TextSanitizer.sanitize(description);
    }
}
