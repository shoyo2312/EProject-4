package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * interaction.comment-events carries a creation and a deletion, both flat JSON objects with no
 * type field, so routing is on the eventType header interaction-service sets — see its
 * InteractionEventPublisher.
 *
 * <p>Reading the payload without that header is not a missing feature, it is a silent wrong
 * answer: CommentDeletedEvent differs from CommentCreatedEvent only by the absence of
 * {@code content}, so Jackson parses it into the creation record with a null field and no
 * complaint, and every comment removal used to add engagement instead of taking it back —
 * deleting a comment pushed the video up the trending ranking.
 */
@Component
@RequiredArgsConstructor
public class CommentEventConsumer {

    private static final String COMMENT_DELETED = "CommentDeletedEvent";

    private final RecommendationService recommendationService;
    private final InboxService inboxService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.comment-events", groupId = "recommendation-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        // Absent header means a producer older than the deletion event, and everything that topic
        // carried then was a creation — same reasoning as VideoEventConsumer on video.video-events.
        String eventType = eventTypeHeader == null
                ? null
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (COMMENT_DELETED.equals(eventType)) {
            CommentDeletedEvent event = objectMapper.readValue(payload, CommentDeletedEvent.class);
            inboxService.runOnce(event.eventId(), () ->
                    recommendationService.recordComment(String.valueOf(event.videoId()), false));
            return;
        }

        CommentCreatedEvent event = objectMapper.readValue(payload, CommentCreatedEvent.class);
        inboxService.runOnce(event.eventId(), () ->
                recommendationService.recordComment(String.valueOf(event.videoId()), true));
    }
}
