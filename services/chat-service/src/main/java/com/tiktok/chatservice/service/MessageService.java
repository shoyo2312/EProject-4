package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;

public interface MessageService {

    MessageResponse sendMessage(String conversationId, Long senderId, SendMessageRequest request);

    MessagePageResponse listMessages(String conversationId, Long currentUserId, String cursor, int size);

    void markRead(String conversationId, Long currentUserId);
}
