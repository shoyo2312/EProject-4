package com.tiktok.userservice.dto.response;

/**
 * The viewer's relationship to {@code userId} — everything a Follow button needs in one answer.
 * {@code friends} is mutual following, what video-service's FRIENDS visibility means; false for
 * self and for an anonymous viewer, as are {@code following} and {@code requested}.
 */
public record FriendshipResponse(
        Long userId,
        boolean friends,
        /** The viewer follows {@code userId}. */
        boolean following,
        /** The viewer has a pending follow request to {@code userId} — the button reads "Requested". */
        boolean requested,
        /** {@code userId}'s account is private: the one field an anonymous viewer can see true. */
        boolean privateAccount
) {
}
