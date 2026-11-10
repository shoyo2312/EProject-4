package com.tiktok.chatservice.dto.request;

/** Whether the sender is typing right now. Nothing about what they typed ever travels. */
public record TypingRequest(boolean typing) {
}
