package com.tiktok.interactionservice.dto.response;

import java.time.Instant;

/**
 * Whether the caller may comment on a video, answered by the same rule that refuses the comment
 * itself — so a client can replace the comment box with the reason, rather than let someone type
 * a comment only to have it bounce off a 403.
 *
 * <p>Tells the caller no more than posting would: the refusal code already names which of the two
 * settings said no. The account-level cases are split here only because a client words them
 * differently — "nobody may comment" reads as comments being off, "only friends" does not.
 *
 * @param restriction     null when the caller may comment; otherwise why not
 * @param restrictedUntil set with {@link Restriction#COMMENTING_RESTRICTED} only: when the caller may
 *                        comment again
 */
public record CommentPermissionResponse(Restriction restriction, Instant restrictedUntil) {

    public enum Restriction {
        /** The owner turned comments off for this video. Binds the owner too. */
        COMMENTS_DISABLED,
        /** The owner's account accepts comments from nobody but themselves. */
        NO_ONE,
        /** The owner's account accepts comments from mutual followers, and the caller is not one. */
        FRIENDS_ONLY,
        /**
         * The caller, not the video: too many of their comments were refused or removed lately, and
         * they may not comment anywhere until {@code restrictedUntil}.
         */
        COMMENTING_RESTRICTED
    }
}
