package com.tiktok.chatservice.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The messages of the other side this client now holds. The server advances the delivered mark only
 * across messages that are all listed, so a message the client never received can never be covered
 * by an acknowledgement of a later one.
 */
public record AckDeliveredRequest(@NotEmpty @Size(max = 100) List<@Size(max = 64) String> messageIds) {
}
