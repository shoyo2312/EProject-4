package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.ConflictException;

public class ReportAlreadyResolvedException extends ConflictException {

    public ReportAlreadyResolvedException(Long reportId) {
        super("REPORT_ALREADY_RESOLVED", "Report already resolved: " + reportId);
    }
}
