package com.tiktok.adminservice.dto.request;

import com.tiktok.moderation.Surface;
import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import jakarta.validation.constraints.NotBlank;

/**
 * A text to run through the rules and the classifier the way the services would.
 *
 * @param surface where it would appear — links and mentions mean something different in a comment
 *                than in a caption. COMMENT when omitted
 */
public record TextPreviewRequest(
        @NotBlank @MaxCodePoints(2000) String text,
        Surface surface
) {

    public TextPreviewRequest {
        text = TextSanitizer.sanitize(text);
        surface = surface == null ? Surface.COMMENT : surface;
    }
}
