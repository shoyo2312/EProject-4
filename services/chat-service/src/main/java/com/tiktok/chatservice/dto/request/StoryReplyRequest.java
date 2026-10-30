package com.tiktok.chatservice.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only ids and the text: the recipient, the conversation and everything about the story are the server's to find. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoryReplyRequest(
        @NotBlank @Size(max = 64) String storyId,
        @NotBlank @Size(max = 1000) String content,
        @Size(max = 64) String clientRequestId
) {
}
