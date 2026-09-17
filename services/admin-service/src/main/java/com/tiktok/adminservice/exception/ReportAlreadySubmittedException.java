package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.ConflictException;

public class ReportAlreadySubmittedException extends ConflictException {

    public ReportAlreadySubmittedException() {
        super("REPORT_ALREADY_SUBMITTED", "You have already reported this target");
    }
}
