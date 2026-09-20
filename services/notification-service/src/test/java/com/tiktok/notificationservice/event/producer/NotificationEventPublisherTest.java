package com.tiktok.notificationservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.notification.NotificationCreatedEvent;
import com.tiktok.notificationservice.entity.Notification;
import com.tiktok.notificationservice.entity.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private Notification stored() {
        return Notification.builder()
                .id("n1")
                .recipientId(100L)
                .type(NotificationType.LIKE)
                .title("New like")
                .body("Someone liked your video.")
                .referenceId("7")
                .read(false)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void publishCreated_sendsTheWholeEntryKeyedByRecipient() throws Exception {
        new NotificationEventPublisher(kafkaTemplate, objectMapper).publishCreated(stored());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(NotificationEventPublisher.TOPIC), eq("100"), payload.capture());

        NotificationCreatedEvent event = objectMapper.readValue(payload.getValue(), NotificationCreatedEvent.class);
        assertThat(event.recipientId()).isEqualTo(100L);
        assertThat(event.notificationId()).isEqualTo("n1");
        assertThat(event.type()).isEqualTo("LIKE");
        assertThat(event.referenceId()).isEqualTo("7");
    }

    /** The entry is already stored; failing here would only buy a redelivery that stores it twice. */
    @Test
    void publishCreated_swallowsABrokerFailure() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("broker down"));

        assertThatCode(() -> new NotificationEventPublisher(kafkaTemplate, objectMapper).publishCreated(stored()))
                .doesNotThrowAnyException();
    }
}
