package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.response.SendPermission;
import com.tiktok.chatservice.entity.Conversation;

import java.time.Duration;
import java.time.Instant;

/**
 * Who may send into a conversation, as a pure function of what is stored and the clock.
 * {@code MessageServiceImpl} enforces it and {@code ConversationMapper} reports it, so the
 * composer the client draws and the refusal the server gives cannot disagree.
 *
 * <p>The initiator of a pending request has one message per round, and that is a fact about the
 * conversation ({@code lastMessageAt} is set once it is used) rather than a counter. After
 * {@link #RETRY_AFTER} with no accept they get one more round, whether the recipient ignored the
 * request or deleted it. The unique index on the message collection is what makes "one per round"
 * hold under concurrency; this only decides what to show and what to try.
 */
public final class SendPolicy {

    /** How long a request message waits before its sender may send another. */
    public static final Duration RETRY_AFTER = Duration.ofDays(15);

    private SendPolicy() {
    }

    public static SendPermission of(Conversation conversation, Long viewerId) {
        return of(conversation, viewerId, Instant.now());
    }

    public static SendPermission of(Conversation conversation, Long viewerId, Instant now) {
        if (conversation.getRequestState() == null) {
            return SendPermission.ALLOWED;
        }
        if (!conversation.isInitiator(viewerId)) {
            return conversation.isPending() ? SendPermission.AWAITING_ACCEPT : SendPermission.REOPEN_ON_SEND;
        }
        // The initiator: a deleted request reads as an unanswered one, so the two share this branch.
        Instant retryAt = retryAvailableAt(conversation, viewerId);
        return retryAt == null || !now.isBefore(retryAt)
                ? SendPermission.FIRST_MESSAGE_ONLY
                : SendPermission.REQUEST_SENT;
    }

    /** When the initiator may send another request message; null if they have not sent one or are not the initiator. */
    public static Instant retryAvailableAt(Conversation conversation, Long viewerId) {
        if (conversation.getRequestState() == null
                || !conversation.isInitiator(viewerId)
                || conversation.getLastMessageAt() == null) {
            return null;
        }
        return conversation.getLastMessageAt().plus(RETRY_AFTER);
    }
}
