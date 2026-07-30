package com.tiktok.chatservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class ConversationNotFoundException extends ResourceNotFoundException {

    public ConversationNotFoundException(String conversationId) {
        super("CONVERSATION_NOT_FOUND", "Conversation not found: " + conversationId);
    }
}
