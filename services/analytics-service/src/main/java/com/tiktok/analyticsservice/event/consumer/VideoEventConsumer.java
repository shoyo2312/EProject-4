package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.analyticsservice.repository.TrainingDataRepository;
import com.tiktok.event.video.VideoPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * video.video-events carries a publication and a deletion, both flat JSON objects with no type
 * field, so routing is on the eventType header video-service sets — see its VideoEventPublisher.
 *
 * <p>Deletions are read and dropped rather than not routed at all: without the check a deletion
 * payload still parses as a VideoPublishedEvent, just with every field it does not carry set to
 * null, and analytics gains a PUBLISHED row for a video that was not published and a tag wipe for
 * one that has tags.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoEventConsumer {

    private static final String VIDEO_PUBLISHED = "VideoPublishedEvent";

    private final EngagementEventRepository engagementEventRepository;
    private final TrainingDataRepository trainingDataRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "video.video-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        // Absent header means a producer older than the deletion event, and everything that topic
        // carried then was a publication. Nothing else writes to it, so this is not a guess.
        String eventType = eventTypeHeader == null
                ? VIDEO_PUBLISHED
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (!VIDEO_PUBLISHED.equals(eventType)) {
            // Nothing to undo. This is an append-only record of what happened, and a video having
            // been published and watched stays true after it is taken down — the training data
            // behind the ranking model is built from exactly that history.
            log.debug("Ignoring video eventType={}", eventType);
            return;
        }

        VideoPublishedEvent event = objectMapper.readValue(payload, VideoPublishedEvent.class);
        engagementEventRepository.insert(event.eventId(), "PUBLISHED", event.videoId(), event.userId(), event.occurredAt());
        // Tags are only ever announced here. Missing this call means the ranking model trains
        // without the one content feature it has.
        trainingDataRepository.insertTags(event.videoId(), event.tags(), event.occurredAt());
    }
}
