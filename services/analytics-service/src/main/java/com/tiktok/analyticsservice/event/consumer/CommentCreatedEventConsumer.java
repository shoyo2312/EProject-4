package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * interaction.comment-events carries CommentCreatedEvent and CommentDeletedEvent, and their JSON
 * has no field that tells them apart: a deletion parses as a creation with content null. Routing
 * is on the eventType header interaction-service sets; a missing header is a creation, which is
 * all the older producer ever sent.
 */
@Component
@RequiredArgsConstructor
public class CommentCreatedEventConsumer {

    private static final String COMMENT_DELETED = "CommentDeletedEvent";

    private final EngagementEventRepository engagementEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.comment-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        if (eventTypeHeader != null && COMMENT_DELETED.equals(new String(eventTypeHeader, StandardCharsets.UTF_8))) {
            CommentDeletedEvent event = objectMapper.readValue(payload, CommentDeletedEvent.class);
            engagementEventRepository.insert(event.eventId(), "UNCOMMENTED", String.valueOf(event.videoId()), event.userId(), event.occurredAt());
            return;
        }
        CommentCreatedEvent event = objectMapper.readValue(payload, CommentCreatedEvent.class);
        engagementEventRepository.insert(event.eventId(), "COMMENTED", String.valueOf(event.videoId()), event.userId(), event.occurredAt());
    }
}
