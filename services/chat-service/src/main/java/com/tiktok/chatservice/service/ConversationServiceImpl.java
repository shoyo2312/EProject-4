package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.DmAudience;
import com.tiktok.chatservice.client.FriendshipClient;
import com.tiktok.chatservice.client.SettingsClient;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.MessageRequestSummaryResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.exception.CannotMessageSelfException;
import com.tiktok.chatservice.exception.ConversationNotFoundException;
import com.tiktok.chatservice.exception.MessageRequestNotPendingException;
import com.tiktok.chatservice.exception.MessagingNotAllowedException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.exception.NotRequestRecipientException;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.common.exception.DomainException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationRepository conversationRepository;
    private final BlockClient blockClient;
    private final SettingsClient settingsClient;
    private final FriendshipClient friendshipClient;
    private final ChatEventPublisher eventPublisher;
    private final ConversationViews views;

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
            return views.of(reopen(existing), currentUserId);
        }

        boolean mutual = requireMessagingAllowed(currentUserId, otherUserId);
        return views.of(
                createConversation(currentUserId, otherUserId, participantKey, mutual), currentUserId);
    }

    /**
     * What opening an existing thread does to its request state: nothing, except that a pending
     * thread whose sides now follow each other is promoted. In particular opening a request the
     * viewer deleted does not restore it — only sending a message does (see {@link #reopenForSend}),
     * so a tap on a profile's Message button cannot quietly let the other side back in.
     */
    private Conversation reopen(Conversation existing) {
        return existing.isPending() ? promoteIfMutual(existing) : existing;
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
     *
     * <p>Returns whether the two follow each other, which the caller needs next: mutual followers
     * get an ordinary conversation, everyone else a request. EVERYONE lets a non-mutual sender
     * start one; FRIENDS still requires mutual; NO_ONE still refuses.
     */
    private boolean requireMessagingAllowed(Long currentUserId, Long otherUserId) {
        DmAudience audience = settingsClient.dmAudience(otherUserId);
        if (audience == DmAudience.NO_ONE) {
            throw new MessagingNotAllowedException();
        }
        boolean mutual = friendshipClient.areFriends(currentUserId, otherUserId);
        if (audience == DmAudience.FRIENDS && !mutual) {
            throw new MessagingNotAllowedException();
        }
        return mutual;
    }

    private Conversation createConversation(Long currentUserId, Long otherUserId, String participantKey,
                                            boolean mutual) {
        Conversation conversation = Conversation.builder()
                .id(Conversation.newId())
                .participantIds(List.of(currentUserId, otherUserId))
                .participantKey(participantKey)
                .initiatorId(currentUserId)
                .requestState(mutual ? null : MessageRequestState.PENDING)
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
        return views.inbox(conversationRepository.findInbox(currentUserId), currentUserId);
    }

    @Override
    public ConversationResponse getById(Long currentUserId, String conversationId) {
        Conversation conversation = promoteIfMutual(requireParticipant(currentUserId, conversationId));
        return views.of(conversation, currentUserId);
    }

    @Override
    public List<ConversationResponse> listRequests(Long currentUserId) {
        return views.inbox(conversationRepository.findIncomingRequests(currentUserId), currentUserId);
    }

    @Override
    public MessageRequestSummaryResponse requestSummary(Long currentUserId) {
        List<ConversationResponse> requests = listRequests(currentUserId);
        return new MessageRequestSummaryResponse(
                requests.size(), requests.stream().filter(ConversationResponse::hasUnread).count());
    }

    @Override
    public ConversationResponse acceptRequest(Long currentUserId, String conversationId) {
        Conversation conversation = requireRecipient(currentUserId, conversationId);
        if (conversation.getRequestState() == null) {
            return views.of(conversation, currentUserId);
        }
        if (conversation.isDeclined()) {
            throw new MessageRequestNotPendingException();
        }

        boolean changed = conversationRepository.acceptRequest(conversationId, currentUserId, Instant.now());
        Conversation fresh = reload(conversationId);
        if (!changed && fresh.isDeclined()) {
            // A delete landed between the read above and the update.
            throw new MessageRequestNotPendingException();
        }
        if (changed) {
            eventPublisher.conversationChanged(fresh);
        }
        return views.of(fresh, currentUserId);
    }

    @Override
    public ConversationResponse declineRequest(Long currentUserId, String conversationId) {
        Conversation conversation = requireRecipient(currentUserId, conversationId);
        if (conversation.isDeclined()) {
            return views.of(conversation, currentUserId);
        }
        if (!conversation.isPending()) {
            // Already accepted: it is an ordinary conversation now and there is nothing to delete.
            throw new MessageRequestNotPendingException();
        }

        boolean changed = conversationRepository.declineRequest(conversationId, currentUserId, Instant.now());
        Conversation fresh = reload(conversationId);
        if (!changed && fresh.getRequestState() == null) {
            // An accept landed between the read above and the update.
            throw new MessageRequestNotPendingException();
        }
        if (changed) {
            eventPublisher.conversationChanged(fresh);
        }
        return views.of(fresh, currentUserId);
    }

    @Override
    public Conversation promoteIfMutual(Conversation conversation) {
        if (!conversation.isPending()) {
            return conversation;
        }
        Long initiator = conversation.getInitiatorId();
        Long other = conversation.getParticipantIds().stream()
                .filter(id -> !id.equals(initiator)).findFirst().orElse(null);
        if (initiator == null || other == null) {
            return conversation;
        }
        try {
            if (!friendshipClient.areFriends(initiator, other)) {
                return conversation;
            }
        } catch (DomainException e) {
            log.debug("Friendship lookup failed for pending conversation {}; leaving it pending", conversation.getId());
            return conversation;
        }

        boolean changed = conversationRepository.acceptRequest(conversation.getId(), null, Instant.now());
        Conversation fresh = reload(conversation.getId());
        if (changed) {
            eventPublisher.conversationChanged(fresh);
        }
        return fresh;
    }

    @Override
    public Conversation reopenForSend(Conversation conversation, Long senderId) {
        if (!conversation.isDeclined() || conversation.isInitiator(senderId)) {
            return conversation;
        }
        Long other = conversation.getParticipantIds().stream()
                .filter(id -> !id.equals(senderId)).findFirst().orElse(null);
        if (other == null) {
            return conversation;
        }
        // The same gate a brand-new thread passes: the other account's audience can still say no.
        requireMessagingAllowed(senderId, other);

        boolean changed = conversationRepository.reopenDeclined(conversation.getId(), senderId, Instant.now());
        Conversation fresh = reload(conversation.getId());
        if (changed) {
            eventPublisher.conversationChanged(fresh);
        }
        return fresh;
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

    /** A participant who did not start the request: the only one who may answer it. */
    private Conversation requireRecipient(Long currentUserId, String conversationId) {
        Conversation conversation = requireParticipant(currentUserId, conversationId);
        if (conversation.isInitiator(currentUserId)) {
            throw new NotRequestRecipientException();
        }
        return conversation;
    }

    private Conversation reload(String conversationId) {
        return conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }
}
