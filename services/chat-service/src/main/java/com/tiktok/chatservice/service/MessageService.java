package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.MessageUpdateResponse;

public interface MessageService {

    MessageResponse sendMessage(String conversationId, Long senderId, SendMessageRequest request);

    MessagePageResponse listMessages(String conversationId, Long currentUserId, String cursor, int size);

    void markRead(String conversationId, Long currentUserId);

    /**
     * {@link #listMessages} for user-service's "Download your data" job — it asks on behalf of
     * {@code viewerId}, through the internal prefix, with no token. The viewer must be a participant
     * and gets the history the way they see it: what they deleted from their side stays out.
     */
    MessagePageResponse exportMessages(String conversationId, Long viewerId, String cursor, int size);

    /** Sets the user's reaction, replacing any other. Repeating it changes nothing. */
    MessageUpdateResponse react(String conversationId, Long userId, String messageId, String emoji);

    /** Removes the user's reaction. Repeating it changes nothing. */
    MessageUpdateResponse unreact(String conversationId, Long userId, String messageId);

    /** Takes the message back for both sides. Only its sender can; repeating it changes nothing. */
    MessageUpdateResponse recall(String conversationId, Long userId, String messageId);

    /** Deletes the message from the user's own side only. Repeating it changes nothing. */
    void hide(String conversationId, Long userId, String messageId);
}
