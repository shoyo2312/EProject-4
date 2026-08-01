package com.tiktok.userservice.event.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserRegisteredEvent;
import com.tiktok.userservice.repository.InboxEventRepository;
import com.tiktok.userservice.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserRegisteredEventConsumerTest {

    @Mock
    private InboxEventRepository inboxEventRepository;

    @Mock
    private UserProfileService userProfileService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private UserRegisteredEventConsumer newConsumer() {
        return new UserRegisteredEventConsumer(inboxEventRepository, userProfileService, objectMapper);
    }

    @Test
    void onMessage_firstDelivery_claimsAndCreatesProfile() throws Exception {
        UserRegisteredEventConsumer consumer = newConsumer();
        UserRegisteredEvent event = UserRegisteredEvent.of(1L, "alice", "alice@example.com");
        String payload = objectMapper.writeValueAsString(event);

        org.mockito.Mockito.when(inboxEventRepository.tryClaim(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.eq(event.eventId()),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Instant.class))
        ).thenReturn(1);

        consumer.onMessage(payload);

        verify(userProfileService, times(1))
                .createFromRegisteredEvent(event.userId(), event.username(), event.email());
    }

    @Test
    void onMessage_duplicateDelivery_skipsProcessing() throws Exception {
        UserRegisteredEventConsumer consumer = newConsumer();
        UserRegisteredEvent event = UserRegisteredEvent.of(2L, "bob", "bob@example.com");
        String payload = objectMapper.writeValueAsString(event);

        org.mockito.Mockito.when(inboxEventRepository.tryClaim(
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.eq(event.eventId()),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(Instant.class))
        ).thenReturn(0);

        consumer.onMessage(payload);

        verify(userProfileService, never()).createFromRegisteredEvent(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    void onMessage_malformedPayload_propagatesExceptionForErrorHandlerToHandle() {
        UserRegisteredEventConsumer consumer = newConsumer();

        assertThatThrownBy(() -> consumer.onMessage("{not-valid-json"))
                .isInstanceOf(JsonProcessingException.class);

        verify(inboxEventRepository, never()).tryClaim(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(), ArgumentMatchers.any(Instant.class));
        verify(userProfileService, never()).createFromRegisteredEvent(
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }
}
