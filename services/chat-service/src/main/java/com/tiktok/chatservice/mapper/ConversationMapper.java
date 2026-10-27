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

    private boolean hasUnread(Conversation conversation, Long viewerId) {
        if (conversation.getLastMessageAt() == null) {
            return false;
        }
        var lastReadAt = conversation.getLastReadAt() != null
                ? conversation.getLastReadAt().get(String.valueOf(viewerId))
                : null;
        return lastReadAt == null || lastReadAt.isBefore(conversation.getLastMessageAt());
    }
}
