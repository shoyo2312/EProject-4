package com.tiktok.chatservice.dto.response;

/** {@code userId} is typing (or stopped) in the conversation. Temporary: never stored, never a message. */
public record TypingView(String conversationId, Long userId, boolean typing) {
}
