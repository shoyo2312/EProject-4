package com.tiktok.chatservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The newest message of the other side that is on screen, in a thread being looked at. */
public record AckReadRequest(@NotBlank @Size(max = 64) String messageId) {
}
