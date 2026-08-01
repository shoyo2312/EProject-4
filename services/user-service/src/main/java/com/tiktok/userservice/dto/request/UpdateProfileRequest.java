package com.tiktok.userservice.dto.request;

import com.tiktok.userservice.validation.ValidAvatarUrl;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank @Size(max = 100) String displayName,
        @Size(max = 500) String bio,
        // https-only, and host must be in app.avatar.allowed-hosts: rejects javascript:/data:
        // URIs and arbitrary third-party hosts, not just malformed URLs. Blank/null still
        // passes (field is optional).
        @Size(max = 500) @ValidAvatarUrl String avatarUrl
) {
}
