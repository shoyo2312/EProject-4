package com.tiktok.chatservice.websocket;

import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;

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
}
