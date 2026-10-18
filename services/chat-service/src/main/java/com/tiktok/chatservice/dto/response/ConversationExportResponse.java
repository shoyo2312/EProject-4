package com.tiktok.chatservice.dto.response;

import java.time.Instant;
import java.util.List;

/** One of a user's conversations, as much as a data export needs to page its messages. */
public record ConversationExportResponse(
        String id,
        List<Long> participantIds,
        Instant createdAt
) {
}
