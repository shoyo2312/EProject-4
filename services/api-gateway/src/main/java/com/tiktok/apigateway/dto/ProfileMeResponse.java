package com.tiktok.apigateway.dto;

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
        long followingCount
) {
}
