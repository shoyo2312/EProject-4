package com.tiktok.userservice.dto.response;

import java.time.Instant;

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
        long followingCount,
        /**
         * Only approved followers see this account's videos, followers/following lists and
         * reposts. The profile itself and its counters stay public.
         */
        boolean privateAccount,
        /**
         * When the display name may next change. Only the owner is told, on their own profile —
         * null for everyone else, and for the owner when it may change now. When somebody last
         * renamed themselves is not something another account needs to know.
         */
        Instant displayNameChangeableAt
) {
}
