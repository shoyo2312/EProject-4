package com.tiktok.analyticsservice.event.consumer;

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
class UserBirthDateSetEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ViewerProfileRepository viewerProfileRepository;

    private UserBirthDateSetEventConsumer consumer() {
        return new UserBirthDateSetEventConsumer(viewerProfileRepository, objectMapper);
    }

    @Test
    void aLaterBirthDateUpdatesTheViewer() {
        consumer().onMessage("""
                {"eventId":"e1","occurredAt":"2026-11-05T10:00:00Z","userId":7,"birthDate":"2001-05-09"}
                """);

        verify(viewerProfileRepository).upsert(7L, LocalDate.of(2001, 5, 9), Instant.parse("2026-11-05T10:00:00Z"));
    }

    @Test
    void anEventMissingTheDateIsSkipped() {
        consumer().onMessage("""
                {"eventId":"e2","occurredAt":"2026-11-05T10:00:00Z","userId":7}
                """);

        verifyNoInteractions(viewerProfileRepository);
    }
}
