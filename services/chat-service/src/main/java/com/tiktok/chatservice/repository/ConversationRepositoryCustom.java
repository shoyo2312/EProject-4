package com.tiktok.chatservice.repository;

import java.time.Instant;

/**
 * Field-level writes to a conversation, instead of saving the whole document back.
 *
 * <p>Both participants write the same document on every send and every read. Saved whole under
 * {@code @Version}, whichever request had read it second failed with an optimistic-lock error —
 * after its message was already stored. Each write here is a single atomic update of the fields it
 * owns, so two of them can land in any order and both take effect.
 */
public interface ConversationRepositoryCustom {

    /** Moves the preview to this message unless a newer one is already there, and marks it read for its sender. */
    void recordMessage(String conversationId, Long senderId, String content, Instant sentAt);

    /** Advances the user's read marker; never moves it backwards. */
    void markRead(String conversationId, Long userId, Instant readAt);
}
