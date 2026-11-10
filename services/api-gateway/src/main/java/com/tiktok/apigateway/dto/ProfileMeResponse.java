package com.tiktok.apigateway.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Mirrors user-service's UserProfileResponse fields. Kept local since api-gateway only
 * needs it to deserialize GET /api/v1/users/me for aggregation.
 */
public record ProfileMeResponse(
        Long userId,
        String displayName,
        String bio,
        String avatarUrl,
        long followerCount,
        long followingCount,
        Instant displayNameChangeableAt,
        /** Null until the owner gives one; the client asks once after sign-in when it is. */
        LocalDate birthDate
) {
}
