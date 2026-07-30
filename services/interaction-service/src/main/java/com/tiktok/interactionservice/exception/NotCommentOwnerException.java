package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotCommentOwnerException extends ForbiddenException {
    public NotCommentOwnerException(Long commentId) {
        super("NOT_COMMENT_OWNER", "You do not own comment: " + commentId);
    }
}
