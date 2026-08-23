package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * interaction.comment-events carries a creation and a deletion, both flat JSON objects with no
 * type field, so routing is on the eventType header interaction-service sets — see its
 * InteractionEventPublisher. Absent header means a producer older than the deletion event, and
 * everything that topic carried then was a creation — same reasoning as media-worker's
 * VideoEventConsumer on video.video-events.
 *
 * <p>commentCount is updated with an atomic $inc, same reasoning as VideoLikeEventConsumer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentEventConsumer {

    private static final String COMMENT_CREATED = "CommentCreatedEvent";
    private static final String COMMENT_DELETED = "CommentDeletedEvent";

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.comment-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload,
                           @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null
                ? COMMENT_CREATED
                : new String(eventTypeHeader, StandardCharsets.UTF_8);

        if (COMMENT_DELETED.equals(eventType)) {
            CommentDeletedEvent event = objectMapper.readValue(payload, CommentDeletedEvent.class);
            idempotentEventProcessor.runOnce(
                    event.eventId(), event.getClass().getSimpleName(), () -> applyDeleted(event));
        } else {
            CommentCreatedEvent event = objectMapper.readValue(payload, CommentCreatedEvent.class);
            idempotentEventProcessor.runOnce(
                    event.eventId(), event.getClass().getSimpleName(), () -> applyCreated(event));
        }
    }

    private void applyCreated(CommentCreatedEvent event) {
        // Skips soft-deleted and taken-down videos, same reasoning as VideoLikeEventConsumer:
        // both are off every read path, so the comments still arriving are stale clients, and the
        // count they build stays invisible right up until a restore puts the video back showing a
        // number nobody can account for.
        Criteria target = where("_id").is(String.valueOf(event.videoId()))
                .and("deletedAt").is(null)
                .and("status").ne(VideoStatus.TAKEN_DOWN);

        var result = mongoTemplate.updateFirst(
                Query.query(target), new Update().inc("commentCount", 1), Video.class);

        if (result.getMatchedCount() == 0) {
            log.warn("CommentCreatedEvent for unknown, deleted or taken-down videoId={}", event.videoId());
        }
    }

    /**
     * Floored at zero for the same reason VideoLikeEventConsumer floors an unlike: $inc has no
     * floor of its own, and a redelivery outside the idempotency claim's window would otherwise
     * push the count below what was ever really there.
     */
    private void applyDeleted(CommentDeletedEvent event) {
        Criteria target = where("_id").is(String.valueOf(event.videoId()))
                .and("deletedAt").is(null)
                .and("status").ne(VideoStatus.TAKEN_DOWN)
                .and("commentCount").gt(0);

        var result = mongoTemplate.updateFirst(
                Query.query(target), new Update().inc("commentCount", -1), Video.class);

        if (result.getMatchedCount() == 0) {
            log.warn("CommentDeletedEvent matched nothing: videoId={} is unknown, deleted, taken down, "
                            + "or already at zero comments", event.videoId());
        }
    }
}
