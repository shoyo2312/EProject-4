package com.tiktok.chatservice.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * What changed about an existing message — reactions or a recall — and nothing else. It carries no
 * text or sticker, so it is the same for both participants and safe to push to either of them, and
 * a stale copy can only ever say less than the truth. {@code revision} orders updates: a client
 * applies one only if it is newer than what it holds.
 */
public record MessageUpdateResponse(
        String messageId,
        String conversationId,
        long revision,
        Instant recalledAt,
        List<ReactionView> reactions
) {
}
