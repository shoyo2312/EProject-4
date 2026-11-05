package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ConflictException;

import java.util.List;

/**
 * Some picked messages are not evidence the reporter may give: recalled, deleted, hidden or cleared
 * on their side, or not in the conversation at all. The whole report is refused rather than filed
 * short, so the reporter never believes a message was reported when it was not.
 */
public class ReportEvidenceUnavailableException extends ConflictException {

    public ReportEvidenceUnavailableException(List<String> messageIds) {
        super("REPORT_EVIDENCE_UNAVAILABLE", "Some selected messages can no longer be reported: " + String.join(",", messageIds));
    }
}
