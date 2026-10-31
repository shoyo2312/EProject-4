package com.tiktok.adminservice.dto.response;

import java.time.Instant;
import java.util.List;

/** The messages one report was filed with, in the order they were sent, as captured when it was filed. */
public record ReportEvidenceResponse(
        Long reportId,
        String conversationId,
        Long reporterId,
        Long reportedUserId,
        Instant capturedAt,
        List<Item> messages
) {
    /** {@code role} says which side sent it: REPORTER or REPORTED. */
    public record Item(
            String messageId,
            Long senderId,
            String role,
            String type,
            Instant sentAt,
            String text,
            String stickerId,
            String videoId,
            String storyId
    ) {
    }
}
