package com.tiktok.interactionservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddCommentRequest(
        @NotBlank @Size(max = 1000) String content,
        /** Omitted for a top-level comment; set to the top-level comment's id to post a reply. */
        Long parentId
) {
}
