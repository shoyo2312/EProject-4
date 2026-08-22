package com.tiktok.videoservice.event.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Polls videos whose event hasn't been sent yet and forwards them to Kafka.
 * See {@link Video} for why this uses per-document flags instead of a separate outbox
 * collection: this Mongo deployment has no replica set, so no multi-document transactions.
 *
 * <p>Marking is delegated to {@link OutboxDispatcher} so a video is only marked published once
 * the broker acknowledges it — see that class for why doing it inline loses events.
 *
 * <p>Both event types go to one topic under the video's own id as the key, so Kafka orders them
 * per video: no consumer is handed a deletion for a video it has not been told about. Because the
 * topic now carries two shapes, every record leaves here with an {@code eventType} header, which
 * is what consumers route on — the payloads are both flat JSON objects and neither carries a type
 * field of its own.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoEventPublisher {

    private static final String TOPIC = "video.video-events";
    private static final String EVENT_TYPE_HEADER = "eventType";

    private final VideoRepository videoRepository;
    private final OutboxDispatcher outboxDispatcher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000)
    public void publishPending() {
        List<Video> pending = videoRepository
                .findTop100ByEventPublishedAtIsNullAndEventFailedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(pending, this::toPublishedRecord, this::markPublished);

        if (published < pending.size()) {
            log.warn("Published {}/{} video events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    /**
     * A deleted video has to be announced too, or it stays in search results and in the
     * recommendation feed for as long as those stores exist, and its transcoded output sits in
     * MinIO with nothing left that refers to it.
     *
     * <p>Videos deleted before their publication ever went out are announced too. That looks like
     * telling consumers to remove something they never received, and for the two that keep an
     * index it is exactly that — a no-op ZREM and a no-op Elasticsearch delete. But media-worker
     * is not an index: the raw upload is already in MinIO by then, and this event is the only
     * thing that ever refers to it again. Skipping it leaks the object for good, since nothing
     * else knows the key.
     */
    @Scheduled(fixedDelay = 5000)
    public void publishPendingDeletions() {
        List<Video> deleted = videoRepository
                .findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc();

        if (deleted.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(deleted, this::toDeletedRecord, this::markDeletePublished);

        if (published < deleted.size()) {
            log.warn("Published {}/{} video deletion events, the rest stay pending for the next poll",
                    published, deleted.size());
        }
    }

    private ProducerRecord<String, String> toPublishedRecord(Video video) {
        VideoPublishedEvent event = VideoPublishedEvent.of(
                video.getId(), video.getUserId(), video.getTitle(), video.getRawFileUrl(), video.getTags());
        try {
            return record(video.getId(), "VideoPublishedEvent", objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            // Park the row before rethrowing. The dispatcher skips a record it cannot build and
            // moves on, but the poll reads the oldest hundred unpublished videos every five
            // seconds, so without this the same row comes back in the same first position for
            // ever and every video behind it waits on a retry that cannot succeed — the same
            // document serializes the same way every time.
            markEventFailed(video);
            // Unchecked so the dispatcher can skip this one row and still send the rest.
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * No parking counterpart to {@link #toPublishedRecord}: every field of VideoDeletedEvent is an
     * id or a URL this service generated, none of it user-supplied, so there is nothing here for
     * Jackson to refuse. A failure would be a bug in the event class, and one that a parked row
     * would hide rather than surface.
     */
    private ProducerRecord<String, String> toDeletedRecord(Video video) {
        VideoDeletedEvent event = VideoDeletedEvent.of(video.getId(), video.getUserId(), video.getRawFileUrl());
        try {
            return record(video.getId(), "VideoDeletedEvent", objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private ProducerRecord<String, String> record(String key, String eventType, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, payload);
        record.headers().add(new RecordHeader(EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private void markPublished(Video video) {
        video.markEventPublished();
        videoRepository.updateEventPublished(video);
    }

    private void markDeletePublished(Video video) {
        video.markDeleteEventPublished();
        videoRepository.updateDeleteEventPublished(video);
    }

    private void markEventFailed(Video video) {
        log.error("Video {} cannot be serialized into a VideoPublishedEvent; parking it so the "
                + "poll makes progress. It stays unannounced until someone clears eventFailedAt.",
                video.getId());
        video.markEventFailed();
        videoRepository.updateEventFailed(video);
    }
}
