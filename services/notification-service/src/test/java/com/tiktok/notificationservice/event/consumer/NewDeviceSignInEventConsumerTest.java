package com.tiktok.notificationservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.NewDeviceSignInEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The owner's notice, modelled on TikTok's "Account updates": the device, the time in Vietnam and
 * the place, each left out cleanly when unknown.
 */
@ExtendWith(MockitoExtension.class)
class NewDeviceSignInEventConsumerTest {

    /** 21:42 in Vietnam. */
    private static final Instant AT = Instant.parse("2026-10-23T14:42:00Z");

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private NewDeviceSignInEventConsumer consumer() {
        return new NewDeviceSignInEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper);
    }

    private static NewDeviceSignInEvent event(String deviceLabel, String location, Instant at) {
        return new NewDeviceSignInEvent("e-1", at, 7L, deviceLabel, location);
    }

    private void deliver(NewDeviceSignInEvent event) {
        consumer().onMessage(objectMapper.writeValueAsString(event));
    }

    @Test
    void aNewDeviceSignInBecomesOneSystemNotice() {
        when(processedEventRepository.tryClaim(eq("e-1"), anyString())).thenReturn(true);

        deliver(event("Chrome on macOS 27", "Ho Chi Minh City, Vietnam", AT));

        verify(notificationService).create(7L, null, NotificationType.SYSTEM,
                "Your account logged in from a new device",
                "Your account logged in from device Chrome on macOS 27 on 10/23 21:42 +07 in Ho Chi Minh City, "
                        + "Vietnam. If this wasn't you, secure your account immediately.",
                "account-security");
    }

    @Test
    void withoutALabelItSaysANewDevice() {
        assertThat(NewDeviceSignInEventConsumer.body(null, AT, "Ho Chi Minh City, Vietnam")).isEqualTo(
                "Your account logged in from a new device on 10/23 21:42 +07 in Ho Chi Minh City, Vietnam. "
                        + "If this wasn't you, secure your account immediately.");
    }

    @Test
    void withoutAPlaceTheInPartIsLeftOut() {
        assertThat(NewDeviceSignInEventConsumer.body("Chrome on macOS 27", AT, null)).isEqualTo(
                "Your account logged in from device Chrome on macOS 27 on 10/23 21:42 +07. "
                        + "If this wasn't you, secure your account immediately.");
    }

    /** 17:30 UTC is already tomorrow in Vietnam; the notice says the owner's date, not the server's. */
    @Test
    void aSignInAfterFiveUtcIsTheNextDayInVietnam() {
        assertThat(NewDeviceSignInEventConsumer.body("Chrome", Instant.parse("2026-10-23T17:30:00Z"), null))
                .contains(" on 10/24 00:30 +07.");
    }

    @Test
    void aRedeliveredEventWritesNothing() {
        when(processedEventRepository.tryClaim(eq("e-1"), anyString())).thenReturn(false);

        deliver(event("Chrome on macOS 27", "Ho Chi Minh City, Vietnam", AT));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }
}
