package com.tiktok.chatservice.dto.response;

import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.entity.MuteMode;

import java.time.Instant;
import java.util.List;

/**
 * {@code requestState} is null for an ordinary conversation. {@code sendPermission} and
 * {@code retryAvailableAt} are computed for the viewer, so the same conversation answers
 * differently to its two participants. {@code retryAvailableAt} is set only while the viewer's own
 * request message is waiting: the moment they may send one more.
 *
 * <p>The preview fields are the viewer's too: when the last message was recalled the preview says
 * so ({@code lastMessageRecalled}) instead of quoting it, and when the viewer deleted it from their
 * side the preview is the newest message they can still see. {@code lastMessageAt} is not part of
 * that — it stays the time of the newest message sent, since the sending rules and the ordering of
 * the inbox are built on it.
 *
 * <p>The viewer's own choices about their copy are {@code pinnedAt} (null when
 * not pinned; a later pin outranks an earlier one), {@code muteMode} and {@code mutedUntil} as of now
 * on the server's clock (an expired timed mute reads NONE), and {@code inboxHidden} — the viewer
 * deleted the conversation and nothing newer has arrived, so it belongs in no list of theirs.
 *
 * <p>{@code blockedByViewer} describes only the viewer's own block; {@code messagingBlocked}
 * combines both directions without revealing the other participant's choice. These and
 * {@code blockVersion} are null when the state lookup is unavailable.
 */
public record ConversationResponse(
        String id,
        List<Long> participantIds,
        String lastMessageContent,
        MessageType lastMessageType,
        Long lastMessageSenderId,
        Instant lastMessageAt,
        Instant createdAt,
        boolean hasUnread,
        MessageRequestState requestState,
        Long initiatorId,
        SendPermission sendPermission,
        Instant retryAvailableAt,
        boolean lastMessageRecalled,
        Instant pinnedAt,
        MuteMode muteMode,
        Instant mutedUntil,
        boolean inboxHidden,
        Boolean blockedByViewer,
        Boolean messagingBlocked,
        Long blockVersion
) {

    public ConversationResponse(String id, List<Long> participantIds, String lastMessageContent,
                                MessageType lastMessageType, Long lastMessageSenderId, Instant lastMessageAt,
                                Instant createdAt, boolean hasUnread, MessageRequestState requestState,
                                Long initiatorId, SendPermission sendPermission, Instant retryAvailableAt,
                                boolean lastMessageRecalled, Instant pinnedAt, MuteMode muteMode,
                                Instant mutedUntil, boolean inboxHidden) {
        this(id, participantIds, lastMessageContent, lastMessageType, lastMessageSenderId, lastMessageAt,
                createdAt, hasUnread, requestState, initiatorId, sendPermission, retryAvailableAt,
                lastMessageRecalled, pinnedAt, muteMode, mutedUntil, inboxHidden, false, false, 0L);
    }

    public ConversationResponse withBlockState(Boolean ownBlock, Boolean blocked, Long version) {
        return new ConversationResponse(id, participantIds, lastMessageContent, lastMessageType,
                lastMessageSenderId, lastMessageAt, createdAt, hasUnread, requestState, initiatorId,
                sendPermission, retryAvailableAt, lastMessageRecalled, pinnedAt, muteMode, mutedUntil,
                inboxHidden, ownBlock, blocked, version);
    }

    public ConversationResponse(String id, List<Long> participantIds, String lastMessageContent,
                                MessageType lastMessageType, Long lastMessageSenderId, Instant lastMessageAt,
                                Instant createdAt, boolean hasUnread, MessageRequestState requestState,
                                Long initiatorId, SendPermission sendPermission, Instant retryAvailableAt,
                                boolean lastMessageRecalled) {
        this(id, participantIds, lastMessageContent, lastMessageType, lastMessageSenderId, lastMessageAt,
                createdAt, hasUnread, requestState, initiatorId, sendPermission, retryAvailableAt,
                lastMessageRecalled, null, MuteMode.NONE, null, false);
    }

    public ConversationResponse(String id, List<Long> participantIds, String lastMessageContent,
                                MessageType lastMessageType, Long lastMessageSenderId, Instant lastMessageAt,
                                Instant createdAt, boolean hasUnread, MessageRequestState requestState,
                                Long initiatorId, SendPermission sendPermission, Instant retryAvailableAt) {
        this(id, participantIds, lastMessageContent, lastMessageType, lastMessageSenderId, lastMessageAt,
                createdAt, hasUnread, requestState, initiatorId, sendPermission, retryAvailableAt, false);
    }

    public ConversationResponse withPreview(String content, MessageType type, Long senderId, boolean recalled) {
        return new ConversationResponse(id, participantIds, content, type, senderId, lastMessageAt, createdAt,
                hasUnread, requestState, initiatorId, sendPermission, retryAvailableAt, recalled,
                pinnedAt, muteMode, mutedUntil, inboxHidden, blockedByViewer, messagingBlocked, blockVersion);
    }

    public ConversationResponse withViewerChoices(Instant pinned, MuteMode mute, Instant mutedUntilAt, boolean hidden) {
        return new ConversationResponse(id, participantIds, lastMessageContent, lastMessageType,
                lastMessageSenderId, lastMessageAt, createdAt, hasUnread && !hidden, requestState, initiatorId,
                sendPermission, retryAvailableAt, lastMessageRecalled, pinned, mute, mutedUntilAt, hidden,
                blockedByViewer, messagingBlocked, blockVersion);
    }
}
