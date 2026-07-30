package com.tiktok.userservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank @Size(max = 100) String displayName,
        @Size(max = 500) String bio,
        @Size(max = 500) String avatarUrl
) {
}
