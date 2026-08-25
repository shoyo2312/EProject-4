package com.tiktok.userservice.dto.response;

public record UserProfileResponse(
        Long userId,
        /**
         * The account's handle. Null for accounts created before user-service started copying it
         * off the registration event — a client showing it has to cope with its absence.
         */
        String username,
        String displayName,
        String bio,
        String avatarUrl,
        long followerCount,
        long followingCount
) {
}
