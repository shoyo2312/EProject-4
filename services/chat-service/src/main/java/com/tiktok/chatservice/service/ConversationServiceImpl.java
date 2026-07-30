package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.exception.CannotMessageSelfException;
import com.tiktok.chatservice.exception.ConversationNotFoundException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.mapper.ConversationMapper;
import com.tiktok.chatservice.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final ConversationMapper conversationMapper;

    @Override
    public ConversationResponse getOrCreate(Long currentUserId, Long otherUserId) {
        if (currentUserId.equals(otherUserId)) {
            throw new CannotMessageSelfException();
        }

        String participantKey = Conversation.buildParticipantKey(currentUserId, otherUserId);

        Conversation conversation = conversationRepository.findByParticipantKeyAndDeletedAtIsNull(participantKey)
                .orElseGet(() -> createConversation(currentUserId, otherUserId, participantKey));

        return conversationMapper.toResponse(conversation, currentUserId);
    }

    private Conversation createConversation(Long currentUserId, Long otherUserId, String participantKey) {
        Conversation conversation = Conversation.builder()
                .id(Conversation.newId())
                .participantIds(List.of(currentUserId, otherUserId))
                .participantKey(participantKey)
                .build();

        try {
            return conversationRepository.save(conversation);
        } catch (DuplicateKeyException e) {
            // Lost the race to create this pair's conversation — the winner's document is the one to use.
            return conversationRepository.findByParticipantKeyAndDeletedAtIsNull(participantKey)
                    .orElseThrow(() -> e);
        }
    }

    @Override
    public List<ConversationResponse> listForUser(Long currentUserId) {
        return conversationRepository.findByParticipantIdsAndDeletedAtIsNullOrderByLastMessageAtDesc(currentUserId)
                .stream()
                .map(conversation -> conversationMapper.toResponse(conversation, currentUserId))
                .toList();
    }

    @Override
    public ConversationResponse getById(Long currentUserId, String conversationId) {
        Conversation conversation = requireParticipant(currentUserId, conversationId);
        return conversationMapper.toResponse(conversation, currentUserId);
    }

    @Override
    public Conversation requireParticipant(Long currentUserId, String conversationId) {
        Conversation conversation = conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        if (!conversation.hasParticipant(currentUserId)) {
            throw new NotConversationParticipantException(conversationId);
        }

        return conversation;
    }
}
