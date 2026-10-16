package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.mapper.MessageMapper;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Persists to Mongo first, then broadcasts over STOMP — both the REST send endpoint and the
 * WebSocket @MessageMapping entry point funnel through here, so a message is only ever
 * delivered in real time after it is durably stored.
 */
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private static final String TOPIC_PREFIX = "/topic/conversations/";

    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final BlockClient blockClient;

    @Override
    public MessageResponse sendMessage(String conversationId, Long senderId, SendMessageRequest request) {
        Conversation conversation = conversationService.requireParticipant(senderId, conversationId);
        // Per message, not only when the conversation opens: a block placed later has to stop a
        // thread that already exists.
        for (Long participant : conversation.getParticipantIds()) {
            if (!participant.equals(senderId)) {
                blockClient.requireNotBlocked(senderId, participant);
            }
        }

        Instant now = Instant.now();
        Message message = Message.builder()
                .id(Message.newId())
                .conversationId(conversationId)
                .senderId(senderId)
                .content(request.content())
                .sentAt(now)
                .build();
        messageRepository.save(message);

        // A field-level update rather than save(conversation): the other participant writes this
        // same document on every send and read, and a whole-document save under @Version lost
        // that race after the message was already stored. See ConversationRepositoryCustom.
        conversationRepository.recordMessage(conversationId, senderId, request.content(), now);

        MessageResponse response = messageMapper.toResponse(message);
        messagingTemplate.convertAndSend(TOPIC_PREFIX + conversationId, response);

        return response;
    }

    @Override
    public MessagePageResponse listMessages(String conversationId, Long currentUserId, String cursor, int size) {
        conversationService.requireParticipant(currentUserId, conversationId);

        MessageCursor before = MessageCursor.decode(cursor);
        List<Message> fetched = before == null
                ? messageRepository.findPage(conversationId, null, null, size + 1)
                : messageRepository.findPage(conversationId, before.sentAt(), before.id(), size + 1);

        boolean hasMore = fetched.size() > size;
        List<Message> page = hasMore ? fetched.subList(0, size) : fetched;

        String nextCursor = hasMore ? MessageCursor.of(page.get(page.size() - 1)).encode() : null;

        return new MessagePageResponse(page.stream().map(messageMapper::toResponse).toList(), nextCursor, hasMore);
    }

    @Override
    public void markRead(String conversationId, Long currentUserId) {
        conversationService.requireParticipant(currentUserId, conversationId);
        conversationRepository.markRead(conversationId, currentUserId, Instant.now());
    }
}
