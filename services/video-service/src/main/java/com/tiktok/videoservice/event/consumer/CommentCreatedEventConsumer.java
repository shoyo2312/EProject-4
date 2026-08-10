package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.videoservice.entity.Video;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * commentCount is updated with an atomic $inc, same reasoning as VideoLikeEventConsumer. There
 * is no CommentDeletedEvent in event-schema yet, so this only ever increments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentCreatedEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.comment-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        CommentCreatedEvent event = objectMapper.readValue(payload, CommentCreatedEvent.class);

        idempotentEventProcessor.runOnce(
                event.eventId(), event.getClass().getSimpleName(), () -> apply(event));
    }

    private void apply(CommentCreatedEvent event) {
        // Skips soft-deleted videos, same reasoning as VideoLikeEventConsumer.
        var result = mongoTemplate.updateFirst(
                Query.query(where("_id").is(String.valueOf(event.videoId())).and("deletedAt").is(null)),
                new Update().inc("commentCount", 1),
                Video.class);

        if (result.getMatchedCount() == 0) {
            log.warn("CommentCreatedEvent for unknown or deleted videoId={}", event.videoId());
        }
    }
}
