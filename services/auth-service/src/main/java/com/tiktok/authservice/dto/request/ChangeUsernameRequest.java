package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Stricter than {@link RegisterRequest}, which takes any 3-50 characters: the new handle becomes
 * the {@code /@handle} profile link, so it gets the web client's rule — lowercase letters, digits,
 * underscores and periods, starting with a letter. Starting with a letter also keeps it from ever
 * looking like the numeric id the client routes with.
 */
public record ChangeUsernameRequest(
        @NotBlank
        @Pattern(regexp = "^[a-z][a-z0-9._]{2,49}$",
                message = "must be 3-50 lowercase letters, digits, underscores or periods, starting with a letter")
        String username
) {
}
