package com.tiktok.userservice.dto.response;

public record UserProfileResponse(
        Long userId,
        String displayName,
        String bio,
        String avatarUrl,
        long followerCount,
        long followingCount
) {
}
