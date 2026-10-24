package com.tiktok.userservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Create or replace one keyword filter. The keyword is sanitized and checked by the service (one
 * word or hashtag, at most 200 characters); at least one feed must be chosen.
 */
public record SaveKeywordFilterRequest(
        @NotBlank String keyword,
        boolean forYou,
        boolean following,
        boolean friends
) {
}
