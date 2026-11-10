package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class InvalidReportReasonException extends BadRequestException {

    public InvalidReportReasonException(String reason) {
        super("INVALID_REPORT_REASON", "Unknown report reason: " + reason);
    }
}
