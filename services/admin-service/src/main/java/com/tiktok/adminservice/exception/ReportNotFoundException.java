package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class ReportNotFoundException extends ResourceNotFoundException {

    public ReportNotFoundException(Long reportId) {
        super("REPORT_NOT_FOUND", "Report not found: " + reportId);
    }
}
