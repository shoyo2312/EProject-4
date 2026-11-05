package com.tiktok.analyticsservice.event.consumer;

import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository;
import com.tiktok.event.user.UserFollowChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class FollowEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private CreatorAnalyticsRepository creatorAnalyticsRepository;

    private FollowEventConsumer consumer() {
        return new FollowEventConsumer(creatorAnalyticsRepository, objectMapper);
    }

    @Test
    void aFollow_isRecorded() throws Exception {
        UserFollowChangedEvent event = UserFollowChangedEvent.of(1L, 2L, true);

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(creatorAnalyticsRepository).insertFollow(event.eventId(), 1L, 2L, true, event.occurredAt(), "");
    }

    /**
     * An edge missing a side has nothing to count, and used to take the listener down on the
     * unboxing — three retries and a dead letter for a record nothing could have written anyway.
     */
    @Test
    void anEventMissingAFollower_isSkippedNotCrashedOn() throws Exception {
        UserFollowChangedEvent event = new UserFollowChangedEvent("e1", Instant.now(), null, 2L, true, false, null);

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verifyNoInteractions(creatorAnalyticsRepository);
    }

    @Test
    void aFollowFromAVideoKeepsTheVideo() {
        String payload = """
                {"eventId":"e2","occurredAt":"2026-08-20T10:00:00Z","followerId":1,"followingId":2,
                 "followed":true,"requestApproved":false,"sourceVideoId":42}
                """;

        consumer().onMessage(payload);

        verify(creatorAnalyticsRepository).insertFollow("e2", 1L, 2L, true, Instant.parse("2026-08-20T10:00:00Z"), "42");
    }

    @Test
    void aFollowWithoutASourceStoresEmpty() {
        String payload = """
                {"eventId":"e3","occurredAt":"2026-08-20T10:00:00Z","followerId":1,"followingId":2,"followed":true,"requestApproved":false}
                """;

        consumer().onMessage(payload);

        verify(creatorAnalyticsRepository).insertFollow("e3", 1L, 2L, true, Instant.parse("2026-08-20T10:00:00Z"), "");
    }
}
