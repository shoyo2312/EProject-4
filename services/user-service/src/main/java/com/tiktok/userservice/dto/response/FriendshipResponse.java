package com.tiktok.userservice.dto.response;

/**
 * Whether the viewer and {@code userId} are mutual followers — the relationship video-service
 * calls "friends" for its FRIENDS visibility. {@code friends} is false for self and for any pair
 * where either follow edge is missing.
 */
public record FriendshipResponse(
        Long userId,
        boolean friends
) {
}
