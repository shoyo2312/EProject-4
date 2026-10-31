package com.tiktok.chatservice.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * What the reporter could see at {@code capturedAt}, taken by the server. {@code reportedUserId} is
 * the other participant of the conversation: the client never names who it is reporting.
 *
 * <p>Only the fields moderation needs. Media is an id, never a URL: a presigned link would expire
 * before an admin opened it, and a public one would expose what the sender kept private.
 */
public record ReportEvidenceResponse(
        String conversationId,
        Long reportedUserId,
        Instant capturedAt,
        List<Item> messages
) {
    public record Item(
            String messageId,
            Long senderId,
            String type,
            Instant sentAt,
            String text,
            String stickerId,
            String videoId,
            String storyId
    ) {
    }
}
