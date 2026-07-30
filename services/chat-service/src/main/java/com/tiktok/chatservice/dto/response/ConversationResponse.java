package com.tiktok.chatservice.dto.response;

import java.time.Instant;
import java.util.List;

public record ConversationResponse(
        String id,
        List<Long> participantIds,
        String lastMessageContent,
        Long lastMessageSenderId,
        Instant lastMessageAt,
        Instant createdAt,
        boolean hasUnread
) {
}
