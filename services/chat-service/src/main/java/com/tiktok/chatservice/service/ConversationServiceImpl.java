package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.DmAudience;
import com.tiktok.chatservice.client.FriendshipClient;
import com.tiktok.chatservice.client.SettingsClient;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.exception.CannotMessageSelfException;
import com.tiktok.chatservice.exception.ConversationNotFoundException;
import com.tiktok.chatservice.exception.MessagingNotAllowedException;
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
    private final BlockClient blockClient;
    private final SettingsClient settingsClient;
    private final FriendshipClient friendshipClient;

    @Override
    public ConversationResponse getOrCreate(Long currentUserId, Long otherUserId) {
        if (currentUserId.equals(otherUserId)) {
            throw new CannotMessageSelfException();
        }
        blockClient.requireNotBlocked(currentUserId, otherUserId);

        String participantKey = Conversation.buildParticipantKey(currentUserId, otherUserId);

        // Looked up before the audience is, because the audience only decides whether a thread may
        // be started. A client reopening one that exists — the Message button on a profile goes
        // through here — was refused once the other side tightened their setting, while the same
        // thread still sent from the inbox; and a recipient who started it themselves could not
        // be answered from their profile at all.
        Conversation existing = conversationRepository.findByParticipantKeyAndDeletedAtIsNull(participantKey)
                .orElse(null);
        if (existing != null) {
            return conversationMapper.toResponse(existing, currentUserId);
        }

        requireMessagingAllowed(currentUserId, otherUserId);
        return conversationMapper.toResponse(
                createConversation(currentUserId, otherUserId, participantKey), currentUserId);
    }

    /**
     * The recipient's account-level DM audience. Only the recipient's setting is consulted: a
     * sender who accepts messages from nobody can still start a conversation themselves, the same
     * way a video owner can comment on a video that accepts comments from nobody.
     *
     * <p><b>When a conversation is created only, never per message and never on a reopen.</b> A
     * conversation that already exists keeps working when the other side tightens their setting —
     * the same shape as "existing followers won't be affected" on a private account — and
     * enforcing per message would put a user-service call on the hot send path.
     * {@code MessageServiceImpl} keeps its block check and gains nothing.
     */
    private void requireMessagingAllowed(Long currentUserId, Long otherUserId) {
        DmAudience audience = settingsClient.dmAudience(otherUserId);
        boolean allowed = switch (audience) {
            case EVERYONE -> true;
            case FRIENDS -> friendshipClient.areFriends(currentUserId, otherUserId);
            case NO_ONE -> false;
        };

        if (!allowed) {
            throw new MessagingNotAllowedException();
        }
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
