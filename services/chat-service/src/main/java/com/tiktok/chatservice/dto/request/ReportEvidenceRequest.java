package com.tiktok.chatservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** admin-service asking, on a reporter's behalf, for the messages they picked as evidence. */
public record ReportEvidenceRequest(
        @NotNull Long reporterId,
        @NotBlank String conversationId,
        @NotNull @Size(min = 1, max = 50) List<@NotBlank String> messageIds
) {
}
