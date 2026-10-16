package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The author of the comment being replied to and the caller have blocked each other, in one
 * direction or the other — which one is not said, the same way a blocked profile does not say so.
 */
public class ReplyNotAllowedException extends ForbiddenException {

    public ReplyNotAllowedException(Long commentId) {
        super("REPLY_NOT_ALLOWED", "You can't reply to comment: " + commentId);
    }
}
