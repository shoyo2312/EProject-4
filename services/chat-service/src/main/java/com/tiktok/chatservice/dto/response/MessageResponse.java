package com.tiktok.chatservice.dto.response;

import java.time.Instant;

public record MessageResponse(
        String id,
        String conversationId,
        Long senderId,
        String content,
        Instant sentAt
) {
}
