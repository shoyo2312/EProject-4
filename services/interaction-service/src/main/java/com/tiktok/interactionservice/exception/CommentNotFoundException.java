package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class CommentNotFoundException extends ResourceNotFoundException {
    public CommentNotFoundException(Long commentId) {
        super("COMMENT_NOT_FOUND", "Comment not found: " + commentId);
    }
}
