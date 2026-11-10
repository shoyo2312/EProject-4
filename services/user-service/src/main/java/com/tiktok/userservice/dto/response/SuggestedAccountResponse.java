package com.tiktok.userservice.dto.response;

/** One "person you may know", with the tier that found them. */
public record SuggestedAccountResponse(
        UserProfileResponse profile,
        Reason reason,
        /**
         * {@code FOLLOWED_BY} only: the display name (or handle) of one account the viewer follows
         * that follows this one. Null for the other reasons.
         */
        String followedBy
) {

    public enum Reason {
        /** Follows the viewer, who does not follow back. */
        FOLLOWS_YOU,
        /** Followed by at least one account the viewer follows. */
        FOLLOWED_BY,
        /** Top-up by follower count when the graph gives fewer than asked. */
        POPULAR
    }
}
