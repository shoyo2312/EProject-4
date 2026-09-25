package com.tiktok.interactionservice.dto.response;

/**
 * Whether the caller may comment on a video, answered by the same rule that refuses the comment
 * itself — so a client can replace the comment box with the reason, rather than let someone type
 * a comment only to have it bounce off a 403.
 *
 * <p>Tells the caller no more than posting would: the refusal code already names which of the two
 * settings said no. The account-level cases are split here only because a client words them
 * differently — "nobody may comment" reads as comments being off, "only friends" does not.
 *
 * @param restriction null when the caller may comment; otherwise why not
 */
public record CommentPermissionResponse(Restriction restriction) {

    public enum Restriction {
        /** The owner turned comments off for this video. Binds the owner too. */
        COMMENTS_DISABLED,
        /** The owner's account accepts comments from nobody but themselves. */
        NO_ONE,
        /** The owner's account accepts comments from mutual followers, and the caller is not one. */
        FRIENDS_ONLY
    }
}
