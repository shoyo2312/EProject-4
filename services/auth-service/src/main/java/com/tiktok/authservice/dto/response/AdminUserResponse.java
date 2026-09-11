package com.tiktok.authservice.dto.response;

import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserStatus;

import java.time.Instant;
import java.util.List;

/**
 * The admin console's view of an account. Wider than {@link UserResponse} — which the account's
 * own {@code /me} and register replies use — because the console needs the operational fields a
 * user has no reason to see about themselves: when they last had tokens issued, when and why a
 * ban was applied, which providers the account is linked to.
 *
 * @param provider        the first linked provider, or null for an email/password account —
 *                        a shorthand for the common "is this a social account" question
 * @param linkedProviders every provider the account can sign in through; empty for password-only
 */
public record AdminUserResponse(
        Long id,
        String username,
        String email,
        UserRole role,
        UserStatus status,
        boolean emailVerified,
        Instant emailVerifiedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        Instant bannedAt,
        String banReason,
        AuthProvider provider,
        List<AuthProvider> linkedProviders
) {
}
