package com.tiktok.chatservice.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.notification.NotificationCreatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationFanoutTest {

    @Mock
    private SimpMessagingTemplate messaging;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private NotificationFanout fanout() {
        return new NotificationFanout(messaging, objectMapper);
    }

    /**
     * The actor id matters as much as the destination: it leaves as a String, because a Snowflake
     * sent as a JSON number loses its last digits in the browser and the client then looks up an
     * account that does not exist — which is how a notification ended up with no name or avatar.
     */
    @Test
    void onNotificationCreated_sendsToTheRecipientsOwnQueue_withStringIds() throws Exception {
        NotificationCreatedEvent event = NotificationCreatedEvent.of(
                100L, "n1", 360038794325352448L, "LIKE", "New like", "body", "7", Instant.now());

        fanout().onNotificationCreated(objectMapper.writeValueAsString(event));

        ArgumentCaptor<Object> frame = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSendToUser(
                eq("100"), eq(NotificationFanout.DESTINATION), frame.capture());
        NotificationFrame sent = (NotificationFrame) frame.getValue();
        assertThat(sent.notificationId()).isEqualTo("n1");
        assertThat(sent.actorId()).isEqualTo("360038794325352448");
        assertThat(objectMapper.writeValueAsString(sent)).contains("\"actorId\":\"360038794325352448\"");
    }

    @Test
    void onNotificationCreated_dropsAnUnreadablePayload() {
        fanout().onNotificationCreated("not json");

        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }
}
