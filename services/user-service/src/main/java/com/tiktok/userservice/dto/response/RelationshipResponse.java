package com.tiktok.userservice.dto.response;

/**
 * How a viewer relates to {@code userId}, one entry of the batch story-service asks for. Edge-only,
 * like {@link FriendshipResponse}: an id with no account is all false, and {@code blocked},
 * {@code following} and {@code friends} are false for an anonymous viewer and for the viewer's own
 * id, leaving {@code privateAccount} as the one thing a guest can learn.
 */
public record RelationshipResponse(
        Long userId,
        /** Either of the two has blocked the other. */
        boolean blocked,
        /**
         * {@code userId}'s live profile is private. A closed account counts like any other:
         * story-service keeps its own record of closures.
         */
        boolean privateAccount,
        /** The viewer follows {@code userId}. */
        boolean following,
        /** Mutual following: the viewer follows {@code userId} and {@code userId} follows the viewer. */
        boolean friends
) {
}
