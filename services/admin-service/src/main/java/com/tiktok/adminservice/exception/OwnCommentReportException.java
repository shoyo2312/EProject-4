package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** A user reporting a comment they wrote themselves. The web client does not offer it; this stops a direct call. */
public class OwnCommentReportException extends BadRequestException {

    public OwnCommentReportException() {
        super("CANNOT_REPORT_OWN_COMMENT", "You cannot report your own comment");
    }
}
