package com.tiktok.authservice.dto.response;

import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;

import java.time.Instant;
import java.util.List;

public record UserResponse(
        Long id,
        String username,
        String email,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        Instant createdAt,
        /** When the handle may next change; null when it may change now. See UsernameCooldown. */
        Instant usernameChangeableAt,
        /**
         * False for an account made by a social login that has never set one: it gets one only
         * through the reset flow, so a client needs this to offer it.
         */
        boolean hasPassword,
        /**
         * The providers linked to the account. Filled by {@code /me} only, where an account with
         * neither a password nor an address learns which sign-in to reconfirm with before closing
         * itself; empty on every other answer.
         */
        List<AuthProvider> providers
) {
}
