package com.tiktok.videoservice.event.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoPurgedEvent;
import com.tiktok.event.video.VideoVisibilityChangedEvent;
import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Polls videos whose event hasn't been sent yet and forwards them to Kafka.
 * See {@link Video} for why this uses per-document flags instead of a separate outbox
 * collection: this Mongo deployment has no replica set, so no multi-document transactions.
 *
 * <p>Marking is delegated to {@link OutboxDispatcher} so a video is only marked published once
 * the broker acknowledges it — see that class for why doing it inline loses events.
 *
 * <p>All four event types go to one topic under the video's own id as the key, so Kafka orders
 * them per video: no consumer is handed a deletion, or a visibility change, for a video it has not
 * been told about. Because the topic carries several shapes, every record leaves here with an
 * {@code eventType} header, which is what consumers route on — the payloads are all flat JSON
 * objects and none of them carries a type field of its own.
 */
@Slf4j
@Component
public class VideoEventPublisher {

    private static final String TOPIC = "video.video-events";
    private static final String EVENT_TYPE_HEADER = "eventType";

    private final VideoRepository videoRepository;
    private final OutboxDispatcher outboxDispatcher;
    private final ObjectMapper objectMapper;
    private final Duration trashRetention;

    public VideoEventPublisher(VideoRepository videoRepository,
                               OutboxDispatcher outboxDispatcher,
                               ObjectMapper objectMapper,
                               @Value("${video.trash.retention:P30D}") Duration trashRetention) {
        this.videoRepository = videoRepository;
        this.outboxDispatcher = outboxDispatcher;
        this.objectMapper = objectMapper;
        this.trashRetention = trashRetention;
    }

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
     * A deleted video has to be announced too, and right away: this event is the only thing
     * that takes it out of search results and the recommendation feed, and those stores serve
     * their own copy without ever asking this service again. Delaying it by the trash window
     * left owner-deleted videos searchable for a month.
     *
     * <p>Videos deleted before their publication ever went out are announced too. That looks like
     * telling consumers to remove something they never received, and for the two that keep an
     * index it is exactly that — a no-op ZREM and a no-op Elasticsearch delete — which is why
     * every consumer of this event treats an unknown videoId as a no-op.
     *
     * <p>The media is not erased on this event; that is {@link #publishPendingPurges}.
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

    /**
     * The other half of a deletion: erase the media. {@code video.trash.retention} (30 days by
     * default) is a trash window, not an instant purge — a video an owner deletes keeps its
     * media in MinIO and stays watchable from the admin console until this poll's cutoff catches
     * up to it, so a moderator can still review what was removed before it is gone for good.
     * Only media-worker acts on the event; the index consumers already dropped the video on the
     * VideoDeletedEvent that preceded it.
     *
     * <p>Videos deleted before their publication ever went out are purged too. media-worker is
     * not an index: the raw upload is already in MinIO by then, and this event is the only thing
     * that ever refers to it again. Skipping it leaks the object for good, since nothing else
     * knows the key.
     */
    @Scheduled(fixedDelay = 5000)
    public void publishPendingPurges() {
        Instant purgeBefore = Instant.now().minus(trashRetention);
        List<Video> expired = videoRepository.findPendingPurge(purgeBefore, 100);

        if (expired.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(expired, this::toPurgedRecord, this::markPurgePublished);

        if (published < expired.size()) {
            log.warn("Published {}/{} video purge events, the rest stay pending for the next poll",
                    published, expired.size());
        }
    }

    /**
     * The owner moved a video between PUBLIC, FRIENDS and PRIVATE. Announced because visibility
     * rides on the publication event, and that one is sent once: without this poll a video
     * indexed while it was public stays searchable by anyone for as long as the index lives.
     *
     * <p>The event carries the visibility as it is now, not the change that queued it. A row that
     * was flipped twice before the poll got to it therefore announces the second value once and
     * skips the intermediate one, which is the state every consumer wants anyway.
     */
    @Scheduled(fixedDelay = 5000)
    public void publishPendingVisibilityChanges() {
        List<Video> pending = videoRepository
                .findTop100ByVisibilityEventPendingAtIsNotNullOrderByVisibilityEventPendingAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        int published = outboxDispatcher.dispatch(
                pending, this::toVisibilityRecord, this::markVisibilityPublished);

        if (published < pending.size()) {
            log.warn("Published {}/{} video visibility events, the rest stay pending for the next poll",
                    published, pending.size());
        }
    }

    private ProducerRecord<String, String> toPublishedRecord(Video video) {
        VideoPublishedEvent event = VideoPublishedEvent.of(
                video.getId(), video.getUserId(), video.getTitle(), video.getDescription(),
                video.getRawFileUrl(), visibilityName(video), video.getTags());
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

    /** No parking either, for the same reason as {@link #toDeletedRecord}: same fields. */
    private ProducerRecord<String, String> toPurgedRecord(Video video) {
        VideoPurgedEvent event = VideoPurgedEvent.of(video.getId(), video.getUserId(), video.getRawFileUrl());
        try {
            return record(video.getId(), "VideoPurgedEvent", objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private ProducerRecord<String, String> toVisibilityRecord(Video video) {
        VideoVisibilityChangedEvent event = VideoVisibilityChangedEvent.of(
                video.getId(), video.getUserId(), visibilityName(video),
                video.getVisibilityEventPendingAt());
        try {
            return record(video.getId(), "VideoVisibilityChangedEvent",
                    objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException ex) {
            // No parking counterpart, for the same reason as the deletion event: every field is an
            // id or an enum name this service produced, so there is nothing here Jackson can
            // refuse that would not be a bug in the event class.
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * Documents written before the visibility field existed have none. PUBLIC is what they
     * behaved as, so that is what the consumers are told rather than a null they would each have
     * to decide about.
     */
    private static String visibilityName(Video video) {
        return video.getVisibility() == null ? "PUBLIC" : video.getVisibility().name();
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

    private void markPurgePublished(Video video) {
        video.markPurgeEventPublished();
        videoRepository.updatePurgeEventPublished(video);
    }

    /**
     * Conditional on the slot still holding the moment that was announced — an owner who changed
     * visibility again while this record was in flight has written a newer one, and clearing that
     * would drop the change nobody has sent yet.
     */
    private void markVisibilityPublished(Video video) {
        boolean cleared = videoRepository.clearVisibilityEventPending(
                video.getId(), video.getVisibilityEventPendingAt());
        if (cleared) {
            video.markVisibilityEventPublished();
        }
    }

    private void markEventFailed(Video video) {
        log.error("Video {} cannot be serialized into a VideoPublishedEvent; parking it so the "
                + "poll makes progress. It stays unannounced until someone clears eventFailedAt.",
                video.getId());
        video.markEventFailed();
        videoRepository.updateEventFailed(video);
    }
}
