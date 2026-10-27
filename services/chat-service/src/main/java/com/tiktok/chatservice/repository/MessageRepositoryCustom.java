package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Message;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageRepositoryCustom {

    /**
     * Starts a new request round: clears the request flag from messages sent at or before
     * {@code olderThan}. The cutoff is what keeps two concurrent retries from clearing each other's
     * fresh message — only a message from a finished round is older than it.
     */
    long clearStaleRequestFlag(String conversationId, Instant olderThan);

    /**
     * One page of a conversation's messages as {@code viewerId} sees them, newest first, keyset-paged on
     * {@code (sentAt, _id)} — the order {@code conversation_sent_id_idx} keeps them in. Messages the
     * viewer deleted from their side are excluded by the query itself, before the limit, so a page is
     * full and {@code hasMore} true whenever more visible messages exist.
     *
     * @param visibleAfter when the viewer deleted the whole conversation from their side, or null:
     *                     messages sent at or before it are not theirs to see, and are excluded by the
     *                     same query, so paging stays exact across them
     * @param beforeSentAt where the previous page stopped; null for the newest page
     * @param beforeId     the {@code _id} that breaks ties at {@code beforeSentAt}. Null only for a
     *                     cursor issued before ids were part of it, which then pages strictly older
     */
    List<Message> findPage(String conversationId, Long viewerId, Instant visibleAfter, Instant beforeSentAt,
                           String beforeId, int limit);

    /** The message, only if it belongs to this conversation. */
    Optional<Message> findInConversation(String conversationId, String messageId);

    /** Several of a conversation's messages in one query — the originals behind a page of replies. */
    List<Message> findInConversation(String conversationId, Collection<String> messageIds);

    /**
     * Puts {@code emoji} as the user's reaction, replacing any other. True only for the call that
     * changed something: the same reaction again, or a message that is recalled or not there, change
     * nothing. A recall and a reaction racing settle in the database: whichever lands second sees it.
     */
    boolean setReaction(String conversationId, String messageId, Long userId, String emoji);

    /** Removes the user's reaction. True only if there was one to remove. */
    boolean clearReaction(String conversationId, String messageId, Long userId);

    /**
     * Recalls the message, if {@code authorId} sent it and it is not recalled yet: stamps
     * {@code recalledAt} and removes content, sticker and reactions in one update. True only for the
     * call that did it.
     */
    boolean recall(String conversationId, String messageId, Long authorId, Instant at);

    /** Adds the user to the people the message is hidden from. Repeating it changes nothing. */
    void hide(String conversationId, String messageId, Long userId);
}
