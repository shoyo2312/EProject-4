package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.searchservice.index.SearchIndexWriter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * interaction.comment-events carries a creation and a deletion, both flat JSON objects with no
 * type field, so routing is on the eventType header — see interaction-service's
 * InteractionEventPublisher.
 *
 * <p>This used to parse every record as a CommentCreatedEvent. Jackson accepts a deletion into
 * that shape without complaint — the fields it lacks come back null, no exception, no log — so
 * deleting a comment incremented the video's comment count. The header is the only thing that
 * tells the two apart.
 */
@Component
@RequiredArgsConstructor
public class CommentEventConsumer {

    private static final String COMMENT_CREATED = "CommentCreatedEvent";
    private static final String COMMENT_DELETED = "CommentDeletedEvent";

    private final SearchIndexWriter searchIndexWriter;
    private final IdempotentEventProcessor idempotentEventProcessor;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.comment-events", groupId = "search-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null
                ? COMMENT_CREATED
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (COMMENT_DELETED.equals(eventType)) {
            CommentDeletedEvent event = objectMapper.readValue(payload, CommentDeletedEvent.class);
            idempotentEventProcessor.runOnce(event.eventId(), COMMENT_DELETED, () ->
                    searchIndexWriter.applyCounterDelta(
                            String.valueOf(event.videoId()), "commentCount", -1));
        } else {
            CommentCreatedEvent event = objectMapper.readValue(payload, CommentCreatedEvent.class);
            idempotentEventProcessor.runOnce(event.eventId(), COMMENT_CREATED, () ->
                    searchIndexWriter.applyCounterDelta(
                            String.valueOf(event.videoId()), "commentCount", 1));
        }
    }
}
