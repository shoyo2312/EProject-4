package com.tiktok.apigateway.dto;

import java.time.Instant;
import java.util.Optional;

/**
 * Combined "my account" view: identity fields from auth-service + social profile fields
 * from user-service. profileReady is false when user-service hasn't yet consumed the
 * UserRegisteredEvent (Kafka is async), in which case profile fields are null rather than
 * failing the whole request.
 *
 * <p>The two *ChangeableAt fields say when the handle (auth-service, once every 30 days) and the
 * display name (user-service, once every 7) may next change; null means now.
 */
public record MeResponse(
        Long id,
        String username,
        String email,
        String role,
        String status,
        Instant createdAt,
        String displayName,
        String bio,
        String avatarUrl,
        Long followerCount,
        Long followingCount,
        boolean profileReady,
        Instant usernameChangeableAt,
        Instant displayNameChangeableAt
) {

    public static MeResponse from(AuthMeResponse account, Optional<ProfileMeResponse> profile) {
        return profile
                .map(p -> new MeResponse(
                        account.id(), account.username(), account.email(), account.role(),
                        account.status(), account.createdAt(),
                        p.displayName(), p.bio(), p.avatarUrl(),
                        p.followerCount(), p.followingCount(), true,
                        account.usernameChangeableAt(), p.displayNameChangeableAt()))
                .orElseGet(() -> new MeResponse(
                        account.id(), account.username(), account.email(), account.role(),
                        account.status(), account.createdAt(),
                        null, null, null, null, null, false,
                        account.usernameChangeableAt(), null));
    }
}
