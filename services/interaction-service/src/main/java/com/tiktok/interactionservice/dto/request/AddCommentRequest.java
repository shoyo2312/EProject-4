package com.tiktok.interactionservice.dto.request;

import com.tiktok.moderation.text.MaxCodePoints;
import com.tiktok.moderation.text.TextSanitizer;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * The content is sanitized here, before validation runs, so the limit is a limit on what is stored
 * — counted in code points, after invisible characters are gone and decomposed Vietnamese is
 * composed — and a comment made only of invisible characters is blank. See {@link TextSanitizer}.
 *
 * <p>A comment is text, a sticker, or both: content may be blank only when a sticker is named.
 */
public record AddCommentRequest(
        @MaxCodePoints(150) String content,
        /**
         * Omitted for a top-level comment. For a reply, the id of the comment being replied to —
         * either a top-level comment or another reply; the service flattens a reply-to-a-reply onto
         * its top-level ancestor and records the mid reply's author as {@code replyToUserId}.
         */
        Long parentId,
        /** A sticker from chat-service's pack ({@code GET /conversations/stickers}), or omitted. */
        @Size(max = 100) String stickerId
) {

    public AddCommentRequest {
        content = TextSanitizer.sanitize(content);
        stickerId = stickerId == null || stickerId.isBlank() ? null : stickerId.strip();
    }

    /** A text-only comment, as every comment was before stickers. */
    public AddCommentRequest(String content, Long parentId) {
        this(content, parentId, null);
    }

    /** What is stored as the text: never null, empty for a sticker on its own. */
    public String text() {
        return content == null ? "" : content;
    }

    @AssertTrue(message = "must have content or a sticker")
    public boolean isContentOrSticker() {
        return (content != null && !content.isBlank()) || stickerId != null;
    }
}
