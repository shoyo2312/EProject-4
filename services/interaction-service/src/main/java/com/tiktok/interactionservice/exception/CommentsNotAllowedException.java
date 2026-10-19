package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The video owner's account-level comment audience refuses this commenter — they are not a friend,
 * or the owner accepts comments from nobody.
 *
 * <p>Deliberately not {@link CommentsDisabledException}. That one means "the owner closed this
 * video" and the client says exactly that; this one means "not you". Reusing the code would have
 * the UI tell a friends-only viewer that a thread they can see is closed to everyone.
 */
public class CommentsNotAllowedException extends ForbiddenException {

    public CommentsNotAllowedException(Long videoId) {
        super("COMMENTS_NOT_ALLOWED", "The owner does not accept comments from you on video: " + videoId);
    }
}
