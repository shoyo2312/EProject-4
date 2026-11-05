package com.tiktok.adminservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What a reporter says. Who is reported, who sent each message and what it said are not in it: the
 * server reads all three from the conversation.
 */
public record SubmitDirectMessageReportRequest(
        @NotBlank @Size(max = 64) String conversationId,
        @NotBlank @Size(max = 200) String reason,
        @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 64) String> messageIds,
        @NotBlank @Size(max = 64) String requestId
) {
}
