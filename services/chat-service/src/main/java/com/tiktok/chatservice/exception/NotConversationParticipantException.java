package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class NotConversationParticipantException extends ForbiddenException {

    public NotConversationParticipantException(String conversationId) {
        super("NOT_CONVERSATION_PARTICIPANT", "You are not a participant of conversation: " + conversationId);
    }
}
