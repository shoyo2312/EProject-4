package com.tiktok.userservice.dto.response;

import java.time.Instant;
import java.time.LocalDate;

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
        Instant displayNameChangeableAt,
        /**
         * The owner's birth date, on their own profile only; null on anyone else's and for an
         * owner who has not given one yet (the client then asks once).
         */
        LocalDate birthDate
) {

    /** Someone else's view: no birth date. */
    public UserProfileResponse(Long userId, String username, String displayName, String bio, String avatarUrl,
                               long followerCount, long followingCount, boolean privateAccount,
                               Instant displayNameChangeableAt) {
        this(userId, username, displayName, bio, avatarUrl, followerCount, followingCount, privateAccount,
                displayNameChangeableAt, null);
    }
}
