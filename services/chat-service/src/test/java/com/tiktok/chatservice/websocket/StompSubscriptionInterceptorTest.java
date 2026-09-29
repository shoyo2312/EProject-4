package com.tiktok.chatservice.websocket;

import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.DefaultSubscriptionRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class StompSubscriptionInterceptorTest {

    @Mock
    private ConversationService conversationService;

    private Message<byte[]> subscribeTo(String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser((Principal) () -> "42");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeAs(String destination, String role) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser((Principal) () -> "42");
        Map<String, Object> session = new HashMap<>();
        if (role != null) {
            session.put(JwtHandshakeInterceptor.ROLE_ATTRIBUTE, role);
        }
        accessor.setSessionAttributes(session);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> frame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        accessor.setUser((Principal) () -> "42");
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void sendToAnApplicationHandlerIsAllowed() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        Message<byte[]> message = frame(StompCommand.SEND, "/app/chat.send/c1");

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        // The participant check for a send lives in the handler, not here.
        verifyNoInteractions(conversationService);
    }

    /**
     * Every broker-prefixed destination a client could SEND to, which the broker would otherwise
     * relay to subscribers verbatim: a forged chat message, a fake notification in someone else's
     * queue, fake counters and feed entries.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/conversations/c1",
            "/topic/feed",
            "/topic/admin",
            "/topic/videos.v1",
            "/topic/videos.v1.comments",
            "/topic/users.42",
            "/user/7/queue/notifications",
            "/user/queue/notifications",
            "/queue/errors",
            "/app",
            "/application/chat.send/c1"
    })
    void rejectsSendOutsideApplicationPrefix(String destination) {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, destination), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSendWithoutADestination() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, null), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** MESSAGE is a server frame, but a client can still write one, and it is routed like a SEND. */
    @Test
    void rejectsClientMessageFrameToABrokerDestination() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.MESSAGE, "/topic/conversations/c1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonParticipantCannotSubscribeToAConversation() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);
        doThrow(new NotConversationParticipantException("c1"))
                .when(conversationService).requireParticipant(42L, "c1");

        assertThatThrownBy(() -> interceptor.preSend(subscribeTo("/topic/conversations/c1"), null))
                .isInstanceOf(NotConversationParticipantException.class);
    }

    @Test
    void participantSubscribesNormally() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        Message<byte[]> message = subscribeTo("/topic/conversations/c1");

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        verify(conversationService).requireParticipant(42L, "c1");
    }

    @Test
    void videoAndUserTopicsAreNotGated() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        interceptor.preSend(subscribeTo("/topic/videos.v1"), null);
        interceptor.preSend(subscribeTo("/topic/users.42"), null);

        verifyNoInteractions(conversationService);
    }

    @Test
    void ownErrorQueueIsAllowed() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        Message<byte[]> message = subscribeTo("/user/queue/errors");

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }

    /**
     * The simple broker treats a subscription containing {@code *} or {@code **} as an Ant pattern
     * and delivers every matching destination to it — {@code /topic/**} is every conversation on
     * the platform. None of these start with the conversation prefix, so a prefix check lets them
     * all through.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/topic/**",
            "/topic/*/*",
            "/topic/conversation?/**",
            "/topic/conversations/*",
            "/topic/conversations/**",
            "/topic/{x}/c1",
            "/queue/**",
            "/topic/videos.1/../conversations/c1",
            "/topic/something-else"
    })
    void rejectsPatternsAndUnknownDestinations(String destination) {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        assertThatThrownBy(() -> interceptor.preSend(subscribeTo(destination), null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(conversationService);
    }

    /** End to end against the broker's own registry: the rejected pattern really was a wiretap. */
    @Test
    void wildcardSubscriptionWouldHaveMatchedAPrivateConversation() {
        DefaultSubscriptionRegistry registry = new DefaultSubscriptionRegistry();
        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setDestination("/topic/**");
        subscribe.setSessionId("attacker");
        subscribe.setSubscriptionId("s1");
        registry.registerSubscription(MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()));

        SimpMessageHeaderAccessor send = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        send.setDestination("/topic/conversations/12345");

        assertThat(registry.findSubscriptions(MessageBuilder.createMessage(new byte[0], send.getMessageHeaders())))
                .containsKey("attacker");
    }

    @Test
    void adminTopicIsOpenToAdmins() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        Message<byte[]> message = subscribeAs("/topic/admin", "ADMIN");

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        verifyNoInteractions(conversationService);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"USER", "admin", ""})
    void adminTopicIsRefusedToEveryoneElse(String role) {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        assertThatThrownBy(() -> interceptor.preSend(subscribeAs("/topic/admin", role), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A session with no attributes at all — the shape every existing test here builds. */
    @Test
    void adminTopicIsRefusedWithoutSessionAttributes() {
        StompSubscriptionInterceptor interceptor = new StompSubscriptionInterceptor(conversationService);

        assertThatThrownBy(() -> interceptor.preSend(subscribeTo("/topic/admin"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
