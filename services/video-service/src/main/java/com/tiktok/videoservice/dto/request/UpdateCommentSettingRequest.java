package com.tiktok.videoservice.dto.request;

import jakarta.validation.constraints.NotNull;

/** Body of {@code PATCH /api/v1/videos/{videoId}/comments-setting}. */
public record UpdateCommentSettingRequest(
        @NotNull Boolean disabled
) {
}
