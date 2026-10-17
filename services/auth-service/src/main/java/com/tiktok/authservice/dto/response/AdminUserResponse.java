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
 * @param deactivatedAt   when the owner closed it, or null; kept through a ban, so a BANNED
 *                        account can be closed as well
 * @param deletionScheduledAt when the owner's deletion runs — even while BANNED — or null
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
        Instant bannedUntil,
        String banReason,
        Instant deactivatedAt,
        Instant deletionScheduledAt,
        AuthProvider provider,
        List<AuthProvider> linkedProviders
) {
}
