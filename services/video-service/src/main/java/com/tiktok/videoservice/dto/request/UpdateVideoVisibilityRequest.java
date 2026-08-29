package com.tiktok.videoservice.dto.request;

import com.tiktok.videoservice.entity.VideoVisibility;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PATCH /api/v1/videos/{videoId}/visibility}. */
public record UpdateVideoVisibilityRequest(
        @NotNull VideoVisibility visibility
) {
}
