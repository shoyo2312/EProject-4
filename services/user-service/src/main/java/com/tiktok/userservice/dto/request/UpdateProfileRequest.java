package com.tiktok.userservice.dto.request;

import com.tiktok.userservice.validation.ValidAvatarUrl;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PATCH semantics: a null field means "leave unchanged", so a client can send only the field it
 * edits without wiping the rest. To clear an optional field the client sends an empty string,
 * which is stored as null (see UserProfile.updateProfile).
 */
public record UpdateProfileRequest(
        // Optional like the others, but displayName is NOT NULL in the DB, so it can be omitted
        // yet never cleared: whitespace-only is rejected here rather than blanking the name.
        @Pattern(regexp = ".*\\S.*", message = "displayName must not be blank")
        @Size(max = 100) String displayName,
        @Size(max = 500) String bio,
        // https-only, and host must be in app.avatar.allowed-hosts: rejects javascript:/data:
        // URIs and arbitrary third-party hosts, not just malformed URLs. Blank/null still
        // passes (field is optional).
        @Size(max = 500) @ValidAvatarUrl String avatarUrl
) {
}
