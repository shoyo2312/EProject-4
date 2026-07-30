package com.tiktok.videoservice.dto.request;

import com.tiktok.videoservice.entity.VideoVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateVideoRequest(
        @NotBlank @Size(max = 150) String title,
        @Size(max = 2000) String description,
        @NotBlank String rawFileUrl,
        @NotNull VideoVisibility visibility
) {
}
