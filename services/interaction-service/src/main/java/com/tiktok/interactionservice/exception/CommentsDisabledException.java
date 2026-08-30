package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/** The video's owner has turned comments off. Enforced in {@code CommentServiceImpl.addComment}. */
public class CommentsDisabledException extends ForbiddenException {
    public CommentsDisabledException(Long videoId) {
        super("COMMENTS_DISABLED", "Comments are turned off for video: " + videoId);
    }
}
