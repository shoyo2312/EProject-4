package com.tiktok.interactionservice.dto.request;

import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import jakarta.validation.constraints.NotBlank;

/**
 * The content is sanitized here, before validation runs, so the limit is a limit on what is stored
 * — counted in code points, after invisible characters are gone and decomposed Vietnamese is
 * composed — and a comment made only of invisible characters is blank. See {@link TextSanitizer}.
 */
public record AddCommentRequest(
        @NotBlank @MaxCodePoints(150) String content,
        /**
         * Omitted for a top-level comment. For a reply, the id of the comment being replied to —
         * either a top-level comment or another reply; the service flattens a reply-to-a-reply onto
         * its top-level ancestor and records the mid reply's author as {@code replyToUserId}.
         */
        Long parentId
) {

    public AddCommentRequest {
        content = TextSanitizer.sanitize(content);
    }
}
