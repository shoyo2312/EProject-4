package com.tiktok.interactionservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.CommentRemovedEvent;
import com.tiktok.interactionservice.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * admin.moderation-events carries several unrelated event types (UserBanned, VideoTakenDown, ...)
 * whose JSON shapes overlap, so routing relies on the eventType Kafka header set by admin-service's
 * OutboxPublisher rather than on the payload.
 *
 * <p>No inbox table. The single write this consumer performs is an LWT conditioned on the comment
 * still being live, so a redelivery finds nothing to do and returns without touching the counter —
 * the property a claim table would otherwise have to provide.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String COMMENT_REMOVED = "CommentRemovedEvent";

    private final CommentService commentService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "interaction-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null ? null : new String(eventTypeHeader);

        if (COMMENT_REMOVED.equals(eventType)) {
            CommentRemovedEvent event = objectMapper.readValue(payload, CommentRemovedEvent.class);
            commentService.removeByAdmin(event.videoId(), event.commentId());
        } else if (eventType == null) {
            // A moderation event with no eventType header is a producer bug. Warned rather than
            // thrown: retrying cannot add a header, and this service would only DLQ every other
            // service's headerless events too.
            log.warn("Moderation event without an eventType header, dropped: {}", payload);
        } else {
            // UserBanned, VideoTakenDown, ... — other services' events on a shared topic.
            log.debug("Ignoring moderation eventType={}", eventType);
        }
    }
}
