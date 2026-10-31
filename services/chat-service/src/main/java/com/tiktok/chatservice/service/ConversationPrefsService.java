package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.MuteRequest;
import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MuteMode;
import com.tiktok.chatservice.exception.PinLimitReachedException;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ChatUserPrefsRepositoryCustom.PinOutcome;
import com.tiktok.chatservice.repository.ConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * What a participant decides about their own copy of a conversation: pin, mute, and deleting it from
 * their side. Every call acts on the caller's own preferences only — the id comes from the principal
 * and the caller must be a participant — and every change is pushed to the caller's other tabs and
 * devices, never to the other participant.
 *
 * <p>None of it touches the stored {@code Conversation} except {@code markRead} on delete, so none of
 * it can change what the other side sees, who may send, or the request state.
 */
@Service
@RequiredArgsConstructor
public class ConversationPrefsService {

    private final ConversationService conversationService;
    private final ChatUserPrefsRepository prefsRepository;
    private final ConversationRepository conversationRepository;
    private final ChatEventPublisher eventPublisher;
    private final ConversationViews views;

    /** Refuses (and unpins nothing) when the viewer already has three others pinned. Pinning a pinned one changes nothing. */
    public ConversationResponse pin(Long userId, String conversationId) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        PinOutcome outcome = prefsRepository.pin(userId, conversationId, Instant.now());
        if (outcome == PinOutcome.FULL) {
            throw new PinLimitReachedException();
        }
        return changed(conversation, userId, outcome == PinOutcome.PINNED);
    }

    public ConversationResponse unpin(Long userId, String conversationId) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        prefsRepository.unpin(userId, conversationId);
        return changed(conversation, userId, true);
    }

    public ConversationResponse mute(Long userId, String conversationId, MuteRequest.Length length) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        if (length == MuteRequest.Length.INDEFINITE) {
            prefsRepository.mute(userId, conversationId, MuteMode.INDEFINITE, null);
        } else {
            // The server's clock decides when it ends: a device with a wrong clock cannot shorten or lengthen it.
            prefsRepository.mute(userId, conversationId, MuteMode.TIMED, Instant.now().plus(length.span()));
        }
        return changed(conversation, userId, true);
    }

    public ConversationResponse unmute(Long userId, String conversationId) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        prefsRepository.unmute(userId, conversationId);
        return changed(conversation, userId, true);
    }

    /**
     * Deletes the conversation from the caller's side: everything sent up to now stops being theirs to
     * see, and it leaves their inbox and pins. The other participant keeps everything. A message sent
     * after this moment shows up as usual and brings the conversation back — without the history. Repeating it
     * moves the boundary forward and is otherwise a no-op.
     *
     * <p>Not {@code declineRequest} (the recipient's answer to a request, which changes what the
     * other side may do) and not hiding one message: this changes no sending rule and no stored state
     * of the conversation, so deleting a request thread does not hand anyone a turn.
     */
    public void deleteForMe(Long userId, String conversationId) {
        conversationService.requireParticipant(userId, conversationId);
        Instant now = Instant.now();
        prefsRepository.clear(userId, conversationId, now);
        // What was unread is gone with it; a later message is unread again on its own.
        conversationRepository.markRead(conversationId, userId, now);
        eventPublisher.toUser(userId, ChatEvent.removed(conversationId));
    }

    private ConversationResponse changed(Conversation conversation, Long userId, boolean push) {
        Conversation fresh = conversationRepository.findByIdAndDeletedAtIsNull(conversation.getId()).orElse(conversation);
        if (push) {
            eventPublisher.conversationChangedFor(fresh, userId);
        }
        return views.of(fresh, userId);
    }
}
