package com.tiktok.chatservice.dto.response;

/**
 * Two numbers that mean different things: {@code pending} is every request waiting on the viewer's
 * answer; {@code unread} is the subset they have not opened yet. Neither is a count of messages,
 * and neither is part of the ordinary inbox's unread.
 */
public record MessageRequestSummaryResponse(long pending, long unread) {
}
