package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MessageType;

import java.time.Instant;
import java.util.List;

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
    void recordMessage(String conversationId, Long senderId, MessageType type, String content, Instant sentAt);

    /**
     * The newest message was recalled: the preview stops quoting it. Matches on the preview's own
     * time and sender, so recalling an older message — or one that a newer send has already replaced
     * as the preview — changes nothing. {@code lastMessageAt} is deliberately not touched: the
     * sending rules (and the 15-day retry) are built on it, and a recall must not hand anyone a turn.
     */
    void markLastMessageRecalled(String conversationId, Long authorId, Instant sentAt);

    /** Notes that the user deleted a message on their side, so their preview is worked out from their own history. */
    void markPreviewHiddenBy(String conversationId, Long userId);

    /**
     * Unread bookkeeping only: advances the time up to which the user has caught up, never backwards.
     * It is not a receipt — nothing the other side can see follows from it. Used when the user deletes
     * a conversation from their side, and by {@link #advanceMark} to keep "unread" in step with what
     * they actually read.
     */
    void markRead(String conversationId, Long userId, Instant readAt);

    /** Which of a participant's two marks to move. */
    enum MarkKind {
        DELIVERED("deliveredMark"),
        READ("readMark");

        private final String field;

        MarkKind(String field) {
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /**
     * Moves the user's mark to {@code (sentAt, messageId)} — only if that is a later position than the
     * mark now holds (or there is none), in one conditional update, so racing acknowledgements, repeats
     * and late responses can never move it back and never rewrite {@code at} of an unchanged mark.
     * Moving the read mark also moves {@code lastReadAt} to the message's own time, so "unread" never
     * counts a message that arrived after the one that was read.
     *
     * @return true only for the call that advanced it
     */
    boolean advanceMark(String conversationId, Long userId, MarkKind kind, String messageId, Instant sentAt, Instant at);

    /**
     * PENDING to ordinary, in one conditional update: true only for the call that made the change.
     * {@code recipientId} restricts it to the non-initiator (the person answering); null lets the
     * server do it itself, when the two sides turn out to follow each other.
     */
    boolean acceptRequest(String conversationId, Long recipientId, Instant at);

    /** PENDING to DECLINED, by the recipient only; true only for the call that made the change. */
    boolean declineRequest(String conversationId, Long recipientId, Instant at);

    /** DECLINED back to ordinary, for the decliner reaching out themself; true only for the call that did it. */
    boolean reopenDeclined(String conversationId, Long userId, Instant at);

    /**
     * DECLINED back to PENDING, for the initiator's retry once the waiting period has passed: the
     * request shows up in the recipient's box again. True only for the call that made the change.
     */
    boolean rependRequest(String conversationId, Long initiatorId, Instant at);

    /**
     * What the viewer's inbox shows: ordinary conversations, and requests the viewer started
     * (their own pending or deleted one). Requests waiting on the viewer, and ones the viewer
     * deleted, are not here. Newest first.
     */
    List<Conversation> findInbox(Long userId);

    /** Pending requests waiting on the viewer that carry their one message, newest first. */
    List<Conversation> findIncomingRequests(Long userId);
}
