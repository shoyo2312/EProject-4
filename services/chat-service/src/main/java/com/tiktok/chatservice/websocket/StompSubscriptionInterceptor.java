package com.tiktok.chatservice.websocket;

import com.tiktok.chatservice.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.security.Principal;

/**
 * Authorizes STOMP SUBSCRIBE frames.
 *
 * <p>The simple broker delivers to whoever asks: a client that knows a conversation id can
 * subscribe to its topic and read the thread live, and conversation ids are Snowflakes, which
 * are guessable from a timestamp. The REST paths all go through
 * {@link ConversationService#requireParticipant}; this puts the socket behind the same check.
 *
 * <p>Only the conversation namespace is guarded. {@code /topic/videos.*} and
 * {@code /topic/users.*} carry counters and public comments — the same numbers the unauthenticated
 * REST endpoints hand out — so there is nothing there to gate.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompSubscriptionInterceptor implements ChannelInterceptor {

    private static final String CONVERSATION_PREFIX = "/topic/conversations/";

    private final ConversationService conversationService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.SUBSCRIBE != accessor.getCommand()) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(CONVERSATION_PREFIX)) {
            return message;
        }

        String conversationId = destination.substring(CONVERSATION_PREFIX.length());
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new IllegalArgumentException("Not authenticated");
        }

        // Throws NotConversationParticipantException / ConversationNotFoundException, which the
        // broker turns into a STOMP ERROR frame for this client and nothing else.
        conversationService.requireParticipant(Long.valueOf(principal.getName()), conversationId);
        return message;
    }
}
