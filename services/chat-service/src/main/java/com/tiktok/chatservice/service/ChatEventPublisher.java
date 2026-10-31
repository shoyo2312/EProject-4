package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.dto.response.ReceiptView;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.dto.response.MessageUpdateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes {@link ChatEvent}s to one user's own queue. {@code convertAndSendToUser} resolves to the
 * recipient's session, so a client only ever receives its own; {@code StompSubscriptionInterceptor}
 * allows the matching subscription.
 *
 * <p>Never throws. The row is already stored when this runs, and a participant who misses a frame
 * catches up from REST on reconnect, so a broker hiccup must not turn a stored message into an error.
 */
@Slf4j
@Component
public class ChatEventPublisher {

    private static final String QUEUE = "/queue/chat";

    private final SimpMessagingTemplate messagingTemplate;
    private final ConversationViews views;

    /**
     * {@code @Lazy} breaks a cycle: the broker config builds {@code StompSubscriptionInterceptor},
     * which needs {@code ConversationService}, which publishes through here, which needs the
     * messaging template — and that is built by the broker config. The template is only used at
     * send time, long after the context is up.
     */
    public ChatEventPublisher(@Lazy SimpMessagingTemplate messagingTemplate, @Lazy ConversationViews views) {
        this.messagingTemplate = messagingTemplate;
        this.views = views;
    }

    public void toUser(Long userId, ChatEvent event) {
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), QUEUE, event);
        } catch (RuntimeException e) {
            log.warn("Could not push {} for conversation {} to user {}", event.type(), event.conversationId(), userId, e);
        }
    }

    /** The conversation as each participant sees it now — their own send permission and preview included. */
    public void conversationChanged(Conversation conversation) {
        for (Long participant : conversation.getParticipantIds()) {
            toUser(participant, ChatEvent.conversation(views.of(conversation, participant)));
        }
    }

    /** The conversation as one participant sees it — for a change that is theirs alone, like deleting a message for themselves. */
    public void conversationChangedFor(Conversation conversation, Long userId) {
        toUser(userId, ChatEvent.conversation(views.of(conversation, userId)));
    }

    /** The other participants learn how far {@code receipt.userId()} has got; that participant is not told. */
    public void receipt(Conversation conversation, ReceiptView receipt) {
        for (Long participant : conversation.getParticipantIds()) {
            if (!participant.equals(receipt.userId())) {
                toUser(participant, ChatEvent.receipt(receipt));
            }
        }
    }

    /** Reactions or a recall: identical for both participants, so one payload goes to each. */
    public void messageUpdated(Conversation conversation, MessageUpdateResponse update) {
        for (Long participant : conversation.getParticipantIds()) {
            toUser(participant, ChatEvent.updated(update));
        }
    }
}
