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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Authorizes STOMP SUBSCRIBE frames.
 *
 * <p>The simple broker delivers to whoever asks: a client that knows a conversation id can
 * subscribe to its topic and read the thread live, and conversation ids are Snowflakes, which
 * are guessable from a timestamp. The REST paths all go through
 * {@link ConversationService#requireParticipant}; this puts the socket behind the same check.
 *
 * <p>An allowlist, not a prefix check. The broker reads a destination containing {@code *} as an
 * Ant pattern and delivers everything it matches, so {@code /topic/**} — which does not start with
 * the conversation prefix — was a subscription to every conversation on the platform. Only the
 * exact shapes the clients use get through; anything else, patterns included, is refused.
 *
 * <p>{@code /topic/videos.*} and {@code /topic/users.*} carry counters and public comments — the
 * same numbers the unauthenticated REST endpoints hand out — so they only have to be well-formed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StompSubscriptionInterceptor implements ChannelInterceptor {

    private static final Pattern CONVERSATION = Pattern.compile("^/topic/conversations/([0-9A-Za-z_-]+)$");

    private static final List<Pattern> PUBLIC_DESTINATIONS = List.of(
            Pattern.compile("^/topic/videos\\.[0-9A-Za-z_-]+(\\.comments)?$"),
            Pattern.compile("^/topic/users\\.[0-9A-Za-z_-]+$"),
            // Rewritten per session by the user-destination resolver, so they only ever reach the
            // subscriber's own queue.
            Pattern.compile("^/user/queue/errors$"),
            Pattern.compile("^/user/queue/notifications$"));

    private final ConversationService conversationService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.SUBSCRIBE != accessor.getCommand()) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            throw new IllegalArgumentException("Subscription without a destination");
        }
        if (PUBLIC_DESTINATIONS.stream().anyMatch(allowed -> allowed.matcher(destination).matches())) {
            return message;
        }

        Matcher conversation = CONVERSATION.matcher(destination);
        if (!conversation.matches()) {
            log.warn("Refused subscription to {}", destination);
            throw new IllegalArgumentException("Subscription to " + destination + " is not allowed");
        }

        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new IllegalArgumentException("Not authenticated");
        }

        // Throws NotConversationParticipantException / ConversationNotFoundException, which the
        // broker turns into a STOMP ERROR frame for this client and nothing else.
        conversationService.requireParticipant(Long.valueOf(principal.getName()), conversation.group(1));
        return message;
    }
}
