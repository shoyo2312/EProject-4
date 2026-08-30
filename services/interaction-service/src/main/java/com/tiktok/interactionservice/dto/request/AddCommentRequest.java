package com.tiktok.interactionservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddCommentRequest(
        @NotBlank @Size(max = 1000) String content,
        /**
         * Omitted for a top-level comment. For a reply, the id of the comment being replied to —
         * either a top-level comment or another reply; the service flattens a reply-to-a-reply onto
         * its top-level ancestor and records the mid reply's author as {@code replyToUserId}.
         */
        Long parentId
) {
}
