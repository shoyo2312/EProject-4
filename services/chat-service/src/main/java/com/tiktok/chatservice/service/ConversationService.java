package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.entity.Conversation;

import java.util.List;

public interface ConversationService {

    ConversationResponse getOrCreate(Long currentUserId, Long otherUserId);

    List<ConversationResponse> listForUser(Long currentUserId);

    ConversationResponse getById(Long currentUserId, String conversationId);

    /** Loads the raw entity and asserts {@code currentUserId} is a participant, for use by MessageService. */
    Conversation requireParticipant(Long currentUserId, String conversationId);
}
