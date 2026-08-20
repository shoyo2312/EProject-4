package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoViewedEvent;
import com.tiktok.videoservice.entity.Video;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * viewCount, kept the same way likeCount is: an atomic $inc, because a read-modify-write would
 * lose updates under concurrent views and thrash against the @Version optimistic lock. Views are
 * the highest-frequency event of the three, so this matters most here.
 *
 * <p>Deduplication is interaction-service's job, not this consumer's — an event only exists for a
 * view that already survived the per-viewer window there. What this side guards is redelivery of
 * the same event, which the eventId claim covers. There is no lower-bound guard like the unlike
 * path needs: views only ever go up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoViewedEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.view-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoViewedEvent event = objectMapper.readValue(payload, VideoViewedEvent.class);

        idempotentEventProcessor.runOnce(
                event.eventId(), event.getClass().getSimpleName(), () -> apply(event));
    }

    private void apply(VideoViewedEvent event) {
        // deletedAt in the match for the same reason as the like consumer: interaction-service is
        // only as fresh as the last event it saw, so views keep arriving for a video its owner
        // already removed, and counting them moves a number nothing will display again.
        Criteria target = where("_id").is(String.valueOf(event.videoId())).and("deletedAt").is(null);

        var result = mongoTemplate.updateFirst(
                Query.query(target), new Update().inc("viewCount", 1), Video.class);

        if (result.getMatchedCount() == 0) {
            log.warn("VideoViewedEvent matched nothing: videoId={} is unknown or deleted", event.videoId());
        }
    }
}
