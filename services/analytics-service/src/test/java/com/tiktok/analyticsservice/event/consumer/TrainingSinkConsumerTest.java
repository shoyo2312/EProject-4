package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.analyticsservice.repository.TrainingDataRepository;
import com.tiktok.event.interaction.VideoWatchEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * These two listeners are the only source of training data in the system. If either stops
 * writing, nothing breaks and no alert fires — the model simply retrains on a shrinking window
 * and quietly gets worse, which is why they are covered rather than left to integration.
 */
@ExtendWith(MockitoExtension.class)
class TrainingSinkConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private TrainingDataRepository trainingDataRepository;

    @Mock
    private EngagementEventRepository engagementEventRepository;

    @Test
    void watchEvent_isStoredWholeBecauseTheRatioIsTheLabel() throws Exception {
        VideoWatchEvent event = new VideoWatchEvent(
                "evt-1", Instant.parse("2026-08-20T10:00:00Z"), 42L, 7L, 9_000L, 10_000L, true);

        new VideoWatchEventConsumer(trainingDataRepository, objectMapper)
                .onMessage(objectMapper.writeValueAsString(event));

        // Both durations, not only the completed flag: a model wants how much was watched, and
        // that cannot be recovered later from a boolean.
        verify(trainingDataRepository).insertWatch(
                "evt-1", "42", 7L, 9_000L, 10_000L, true, Instant.parse("2026-08-20T10:00:00Z"));
    }

    @Test
    void publishedEvent_storesTheTagsTheRankerTrainsOn() throws Exception {
        VideoPublishedEvent event = VideoPublishedEvent.of(
                "vid1", 7L, "A title", "raw.mp4", List.of("dance", "music"));

        new VideoPublishedEventConsumer(engagementEventRepository, trainingDataRepository, objectMapper)
                .onMessage(objectMapper.writeValueAsString(event));

        verify(trainingDataRepository).insertTags(eq("vid1"), eq(List.of("dance", "music")), any());
    }
}
