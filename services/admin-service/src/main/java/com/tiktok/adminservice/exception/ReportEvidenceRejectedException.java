package com.tiktok.adminservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** chat-service refused the picked messages; its code and message are passed on as it gave them. */
public class ReportEvidenceRejectedException extends DomainException {

    public ReportEvidenceRejectedException(String code, String message, HttpStatus status) {
        super(code, message, status);
    }
}
