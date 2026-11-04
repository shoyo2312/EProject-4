package com.tiktok.videoservice.dto.request;

import jakarta.validation.constraints.NotNull;

/** Body of {@code PATCH /api/v1/videos/{videoId}/pin}. */
public record UpdatePinRequest(
        @NotNull Boolean pinned
) {
}
