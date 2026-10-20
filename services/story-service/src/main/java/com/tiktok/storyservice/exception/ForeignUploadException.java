package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * The upload named on a create is not one this service issued to the requester, or it already
 * backs a story. The key is not echoed back: which keys exist is not something to learn.
 */
public class ForeignUploadException extends ForbiddenException {

    public ForeignUploadException() {
        super("FOREIGN_UPLOAD", "mediaUrl must point at a file you uploaded for a new story");
    }
}
