package com.tiktok.chatservice.dto.response;

/** One person's reaction. The same for both participants; the client works out which one is its own. */
public record ReactionView(Long userId, String emoji) {
}
