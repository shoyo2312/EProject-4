package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;

public interface MessageService {

    MessageResponse sendMessage(String conversationId, Long senderId, SendMessageRequest request);

    MessagePageResponse listMessages(String conversationId, Long currentUserId, String cursor, int size);

    void markRead(String conversationId, Long currentUserId);

    /**
     * {@link #listMessages} without the participant check, for user-service's "Download your data"
     * job — it asks on behalf of a participant, through the internal prefix, with no token.
     */
    MessagePageResponse exportMessages(String conversationId, String cursor, int size);
}
