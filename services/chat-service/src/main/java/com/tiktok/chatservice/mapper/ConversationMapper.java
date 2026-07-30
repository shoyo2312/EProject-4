package com.tiktok.chatservice.mapper;

import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.entity.Conversation;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ConversationMapper {

    @Mapping(target = "hasUnread", ignore = true)
    ConversationResponse toResponse(Conversation conversation);

    default ConversationResponse toResponse(Conversation conversation, Long viewerId) {
        ConversationResponse base = toResponse(conversation);
        boolean hasUnread = hasUnread(conversation, viewerId);
        return new ConversationResponse(
                base.id(),
                base.participantIds(),
                base.lastMessageContent(),
                base.lastMessageSenderId(),
                base.lastMessageAt(),
                base.createdAt(),
                hasUnread);
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
