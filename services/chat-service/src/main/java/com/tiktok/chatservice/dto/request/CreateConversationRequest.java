package com.tiktok.chatservice.dto.request;

import jakarta.validation.constraints.NotNull;

public record CreateConversationRequest(
        @NotNull Long participantId
) {
}
