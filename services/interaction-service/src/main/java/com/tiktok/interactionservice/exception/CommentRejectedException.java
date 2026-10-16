package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The comment holds a term beyond review — the worst of the blocklist. Nothing is stored. Which
 * term is not said: that is how a filter gets written around. Everything milder is held for the
 * video's owner instead of refused.
 */
public class CommentRejectedException extends DomainException {

    public CommentRejectedException() {
        super("COMMENT_REJECTED", "This comment goes against our community guidelines.",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
