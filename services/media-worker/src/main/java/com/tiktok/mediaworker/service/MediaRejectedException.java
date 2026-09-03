package com.tiktok.mediaworker.service;

/**
 * The uploaded file cannot be accepted and never will be — too large, too long, or unreadable.
 * Distinct from a transient storage error: VideoEventConsumer turns this into a single FAILED
 * result with no retry, where an IOException still gets the full retry budget.
 */
public class MediaRejectedException extends RuntimeException {
    public MediaRejectedException(String message) {
        super(message);
    }
}
