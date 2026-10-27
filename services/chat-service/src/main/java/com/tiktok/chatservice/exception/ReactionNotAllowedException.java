package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/** A reaction is an interaction, so it follows the sending rules: not while blocked, not before a request is accepted. */
public class ReactionNotAllowedException extends ForbiddenException {

    public ReactionNotAllowedException() {
        super("REACTION_NOT_ALLOWED", "You cannot react in this conversation right now");
    }
}
