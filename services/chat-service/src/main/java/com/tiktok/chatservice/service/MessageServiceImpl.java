package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.mapper.MessageMapper;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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

    @Override
    public MessageResponse sendMessage(String conversationId, Long senderId, SendMessageRequest request) {
        Conversation conversation = conversationService.requireParticipant(senderId, conversationId);

        Instant now = Instant.now();
        Message message = Message.builder()
                .id(Message.newId())
                .conversationId(conversationId)
                .senderId(senderId)
                .content(request.content())
                .sentAt(now)
                .build();
        messageRepository.save(message);

        conversation.recordMessage(senderId, request.content(), now);
        conversationRepository.save(conversation);

        MessageResponse response = messageMapper.toResponse(message);
        messagingTemplate.convertAndSend(TOPIC_PREFIX + conversationId, response);

        return response;
    }

    @Override
    public MessagePageResponse listMessages(String conversationId, Long currentUserId, String cursor, int size) {
        conversationService.requireParticipant(currentUserId, conversationId);

        Instant before = decodeCursor(cursor);
        Pageable pageable = PageRequest.of(0, size + 1, Sort.by(Sort.Direction.DESC, "sentAt"));

        List<Message> fetched = messageRepository
                .findByConversationIdAndDeletedAtIsNullAndSentAtBefore(conversationId, before, pageable);

        boolean hasMore = fetched.size() > size;
        List<Message> page = hasMore ? fetched.subList(0, size) : fetched;

        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1).getSentAt()) : null;

        return new MessagePageResponse(page.stream().map(messageMapper::toResponse).toList(), nextCursor, hasMore);
    }

    @Override
    public void markRead(String conversationId, Long currentUserId) {
        Conversation conversation = conversationService.requireParticipant(currentUserId, conversationId);
        conversation.markRead(currentUserId, Instant.now());
        conversationRepository.save(conversation);
    }

    private Instant decodeCursor(String cursor) {
        if (cursor == null) {
            return Instant.now().plusSeconds(60);
        }
        return Instant.parse(new String(Base64.getDecoder().decode(cursor), StandardCharsets.UTF_8));
    }

    private String encodeCursor(Instant instant) {
        return Base64.getEncoder().encodeToString(instant.toString().getBytes(StandardCharsets.UTF_8));
    }
}
