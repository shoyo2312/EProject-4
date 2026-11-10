package com.tiktok.analyticsservice.event.consumer;

import com.tiktok.analyticsservice.repository.UserSignupEventRepository;
import com.tiktok.analyticsservice.repository.ViewerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserRegisteredEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private UserSignupEventRepository userSignupEventRepository;

    @Mock
    private ViewerProfileRepository viewerProfileRepository;

    private UserRegisteredEventConsumer consumer() {
        return new UserRegisteredEventConsumer(userSignupEventRepository, viewerProfileRepository, objectMapper);
    }

    @Test
    void aRegistrationWithABirthDateFillsViewerProfiles() {
        consumer().onMessage("""
                {"eventId":"e1","occurredAt":"2026-08-20T10:00:00Z","userId":7,"username":"alice",
                 "email":"a@x.io","birthDate":"2001-05-09"}
                """);

        Instant at = Instant.parse("2026-08-20T10:00:00Z");
        verify(userSignupEventRepository).insert("e1", 7L, "alice", at);
        verify(viewerProfileRepository).upsert(7L, LocalDate.of(2001, 5, 9), at);
    }

    /** A social sign-up, or an event from before the field: the sign-up row only. */
    @Test
    void aRegistrationWithoutOneOnlyWritesTheSignup() {
        consumer().onMessage("""
                {"eventId":"e2","occurredAt":"2026-08-20T10:00:00Z","userId":7,"username":"alice","email":"a@x.io"}
                """);

        verify(userSignupEventRepository).insert("e2", 7L, "alice", Instant.parse("2026-08-20T10:00:00Z"));
        verifyNoInteractions(viewerProfileRepository);
    }
}
