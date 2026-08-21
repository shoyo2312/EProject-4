package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoLikeEvent;
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
import org.springframework.stereotype.Component;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * likeCount is updated with an atomic $inc rather than the usual load-mutate-save, since likes
 * happen at much higher frequency than other Video mutations and a read-modify-write would
 * either lose updates under concurrent likes or thrash against the @Version optimistic lock.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoLikeEventConsumer {

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "interaction.like-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        VideoLikeEvent event = objectMapper.readValue(payload, VideoLikeEvent.class);

        idempotentEventProcessor.runOnce(
                event.eventId(), event.getClass().getSimpleName(), () -> apply(event));
    }

    private void apply(VideoLikeEvent event) {
        long delta = event.liked() ? 1 : -1;

        // deletedAt is part of the match, not just the id: interaction-service can only be as
        // fresh as the last event it saw, so likes keep arriving for a video its owner has
        // already removed. Counting them moves a number nothing will ever display and that no
        // later event corrects — the same reason VideoTranscodedEventConsumer skips deleted ids.
        // TAKEN_DOWN alongside deletedAt: a moderated video is off every read path too, so likes
        // still arriving are stale clients, and the count they build stays invisible until a
        // restore puts the video back showing a number nobody can account for.
        Criteria target = where("_id").is(String.valueOf(event.videoId()))
                .and("deletedAt").is(null)
                .and("status").ne(VideoStatus.TAKEN_DOWN);

        // An unlike is only allowed to remove a like that is actually counted here. $inc has no
        // floor, and the events that reach it are not guaranteed to pair up: two partitions can
        // deliver an unlike ahead of its like, and a redelivery older than the 30-day claim TTL
        // is applied a second time. One stray -1 leaves a video showing -1 like forever, since
        // nothing recomputes the counter. Postgres counters guard the same way — see
        // user-service's UserProfileRepository.decrementFollowerCount.
        if (!event.liked()) {
            target = target.and("likeCount").gt(0);
        }

        var result = mongoTemplate.updateFirst(Query.query(target), new Update().inc("likeCount", delta), Video.class);

        if (result.getMatchedCount() == 0) {
            log.warn("VideoLikeEvent(liked={}) matched nothing: videoId={} is unknown, deleted{}",
                    event.liked(), event.videoId(), event.liked() ? "" : ", or already at zero likes");
        }
    }
}
