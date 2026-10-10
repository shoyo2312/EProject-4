package com.tiktok.userservice.event.consumer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UsernameChangedEvent;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.InboxEventRepository;
import com.tiktok.userservice.service.UserProfileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsernameChangedEventConsumerTest {

    @Mock
    private InboxEventRepository inboxEventRepository;

    @Mock
    private UserProfileService userProfileService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UsernameChangedEventConsumer newConsumer() {
        return new UsernameChangedEventConsumer(inboxEventRepository, userProfileService, objectMapper);
    }

    @Test
    void firstDelivery_claimsAndMovesTheHandle() throws Exception {
        UsernameChangedEvent event = UsernameChangedEvent.of(1L, "alice.new", "alice");
        when(inboxEventRepository.tryClaim(anyLong(), eq(event.eventId()), anyString(), any(Instant.class)))
                .thenReturn(1);

        newConsumer().onMessage(objectMapper.writeValueAsString(event));

        verify(userProfileService).applyUsernameChange(1L, "alice.new");
    }

    @Test
    void redelivery_isSkipped() throws Exception {
        UsernameChangedEvent event = UsernameChangedEvent.of(1L, "alice.new", "alice");
        when(inboxEventRepository.tryClaim(anyLong(), eq(event.eventId()), anyString(), any(Instant.class)))
                .thenReturn(0);

        newConsumer().onMessage(objectMapper.writeValueAsString(event));

        verify(userProfileService, never()).applyUsernameChange(anyLong(), anyString());
    }

    /** Propagated so kafka-lib's handler retries it — the registration may just be behind. */
    @Test
    void aRenameAheadOfItsProfile_propagates() throws Exception {
        UsernameChangedEvent event = UsernameChangedEvent.of(1L, "alice.new", "alice");
        when(inboxEventRepository.tryClaim(anyLong(), eq(event.eventId()), anyString(), any(Instant.class)))
                .thenReturn(1);
        doThrow(new UserProfileNotFoundException(1L)).when(userProfileService).applyUsernameChange(1L, "alice.new");

        assertThatThrownBy(() -> newConsumer().onMessage(objectMapper.writeValueAsString(event)))
                .isInstanceOf(UserProfileNotFoundException.class);
    }

    @Test
    void aMalformedPayload_propagatesBeforeClaimingAnything() {
        assertThatThrownBy(() -> newConsumer().onMessage("{not-valid-json"))
                .isInstanceOf(JacksonException.class);

        verify(inboxEventRepository, never()).tryClaim(anyLong(), anyString(), anyString(), any(Instant.class));
    }
}
