package com.tiktok.chatservice.mapper;

import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.SendPermission;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.service.SendPolicy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring")
public interface ConversationMapper {

    @Mapping(target = "hasUnread", ignore = true)
    @Mapping(target = "sendPermission", ignore = true)
    @Mapping(target = "retryAvailableAt", ignore = true)
    @Mapping(target = "blockedByViewer", constant = "false")
    @Mapping(target = "messagingBlocked", constant = "false")
    @Mapping(target = "blockVersion", constant = "0L")
    ConversationResponse toResponse(Conversation conversation);

    default ConversationResponse toResponse(Conversation conversation, Long viewerId) {
        ConversationResponse base = toResponse(conversation);
        Instant now = Instant.now();
        SendPermission permission = SendPolicy.of(conversation, viewerId, now);
        return new ConversationResponse(
                base.id(),
                base.participantIds(),
                base.lastMessageContent(),
                base.lastMessageType() == null && base.lastMessageAt() != null ? MessageType.TEXT : base.lastMessageType(),
                base.lastMessageSenderId(),
                base.lastMessageAt(),
                base.createdAt(),
                hasUnread(conversation, viewerId),
                base.requestState(),
                base.initiatorId(),
                permission,
                permission == SendPermission.REQUEST_SENT ? SendPolicy.retryAvailableAt(conversation, viewerId) : null,
                Boolean.TRUE.equals(conversation.getLastMessageRecalled()));
    }

    /**
     * Whether the other side has sent something the viewer has not caught up on. Measured against when
     * the OTHER participant last sent — not the conversation's last message, which may be the viewer's
     * own: sending a reply without opening the thread does not make what came before it read.
     * Conversations from before per-sender times were kept fall back to the last message, which is
     * then unread unless the viewer sent it.
     */
    private boolean hasUnread(Conversation conversation, Long viewerId) {
        if (conversation.getLastMessageAt() == null) {
            return false;
        }
        String viewer = String.valueOf(viewerId);
        var lastReadAt = conversation.getLastReadAt() != null ? conversation.getLastReadAt().get(viewer) : null;
        var peerSentAt = conversation.getLastSentAt() == null || conversation.getParticipantIds() == null ? null
                : conversation.getParticipantIds().stream()
                        .filter(id -> !id.equals(viewerId)).findFirst()
                        .map(id -> conversation.getLastSentAt().get(String.valueOf(id))).orElse(null);
        if (peerSentAt != null) {
            return lastReadAt == null || lastReadAt.isBefore(peerSentAt);
        }
        if (viewerId.equals(conversation.getLastMessageSenderId())) {
            return false;
        }
        return lastReadAt == null || lastReadAt.isBefore(conversation.getLastMessageAt());
    }
}
