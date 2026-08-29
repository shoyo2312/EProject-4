package com.tiktok.interactionservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.event.interaction.VideoViewedEvent;
import com.tiktok.event.interaction.VideoWatchEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.SneakyThrows;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes inline right after the corresponding Cassandra write succeeds. There is no outbox
 * here — Cassandra has no cross-table transaction to pair one with — so the broker's ack is what
 * stands in for it: {@code send()} hands back a future and throws synchronously only on a
 * serialization error or a full buffer, which means a broker that refuses the record would
 * otherwise be an event dropped in silence that nothing ever retries.
 *
 * <p>These events are no longer nobody's business, which is what the note here used to assume:
 * video-service maintains likeCount, commentCount and viewCount from them, and
 * recommendation-service builds trending and every viewer's tag profile out of them. A dropped one
 * is a counter permanently off by one, because neither side ever recomputes.
 *
 * <p>So the send is confirmed and a failure reaches the caller, whose Cassandra write is then
 * compensated and whose client gets an error worth retrying — see LikeServiceImpl.like for the
 * shape. That trades a Kafka blip for a failed request instead of a silent, permanent divergence
 * between three services' idea of the same number.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionEventPublisher {

    /**
     * Long enough to ride out a leader election, short enough that a person waiting on a like is
     * not left holding the request. Deliberately far below media-worker's 30s: that publisher runs
     * on a Kafka listener with nobody waiting, this one runs inside an HTTP request.
     */
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(5);

    private static final String LIKE_TOPIC = "interaction.like-events";
    private static final String COMMENT_TOPIC = "interaction.comment-events";
    private static final String SHARE_TOPIC = "interaction.share-events";
    private static final String VIEW_TOPIC = "interaction.view-events";
    private static final String WATCH_TOPIC = "interaction.watch-events";
    private static final String EVENT_TYPE_HEADER = "eventType";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publishLike(Long videoId, Long userId, boolean liked) {
        VideoLikeEvent event = VideoLikeEvent.of(videoId, userId, liked);
        confirm(new ProducerRecord<>(
                LIKE_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event)));
    }

    /**
     * {@code interaction.comment-events} now carries two shapes — a creation and a deletion —
     * so every record leaves here with an {@code eventType} header, same reasoning as
     * video-service's VideoEventPublisher on video.video-events.
     */
    @SneakyThrows
    public void publishCommentCreated(Long commentId, Long videoId, Long userId, String content) {
        CommentCreatedEvent event = CommentCreatedEvent.of(commentId, videoId, userId, content);
        confirm(commentRecord(videoId, "CommentCreatedEvent", objectMapper.writeValueAsString(event)));
    }

    @SneakyThrows
    public void publishCommentDeleted(Long commentId, Long videoId, Long userId) {
        CommentDeletedEvent event = CommentDeletedEvent.of(commentId, videoId, userId);
        confirm(commentRecord(videoId, "CommentDeletedEvent", objectMapper.writeValueAsString(event)));
    }

    private ProducerRecord<String, String> commentRecord(Long videoId, String eventType, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(COMMENT_TOPIC, String.valueOf(videoId), payload);
        record.headers().add(new RecordHeader(EVENT_TYPE_HEADER, eventType.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    /** Only for views that survived deduplication — see ViewServiceImpl. */
    @SneakyThrows
    public void publishView(Long videoId, Long userId) {
        VideoViewedEvent event = VideoViewedEvent.of(videoId, userId);
        confirm(new ProducerRecord<>(
                VIEW_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event)));
    }

    /**
     * Every session, unlike {@link #publishView} — see ViewService.recordWatch for why the label
     * stream and the counter stream cannot be the same one. Keyed by video so one video's
     * sessions stay ordered within a partition, matching the other four topics.
     *
     * <p>The one send that is not confirmed. No counter is derived from this topic — it is
     * training rows and tag affinity, where one lost session out of hundreds a day changes
     * nothing, while blocking the request that reports it would put the densest stream this
     * service produces on the critical path of every scroll.
     *
     * <p>Not confirmed is not the same as unwatched: the failure is logged from the callback, so a
     * broker that has stopped taking this topic shows up in the log rather than as a training set
     * that quietly stops growing. Nothing waits on that callback — the request has already
     * returned by the time it runs.
     */
    @SneakyThrows
    public void publishWatch(Long videoId, Long userId, long watchedMs, long durationMs, boolean completed) {
        VideoWatchEvent event = VideoWatchEvent.of(videoId, userId, watchedMs, durationMs, completed);
        kafkaTemplate.send(WATCH_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.warn("Watch session of video {} by user {} was not accepted by the broker "
                                + "and is lost; tag affinity and training rows are missing it: {}",
                                videoId, userId, failure.getMessage());
                    }
                });
    }

    @SneakyThrows
    public void publishShare(Long shareId, Long videoId, Long userId) {
        VideoSharedEvent event = VideoSharedEvent.of(shareId, videoId, userId);
        confirm(new ProducerRecord<>(
                SHARE_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event)));
    }

    /**
     * Sends and waits for the broker to say it has the record. Everything that moves a counter goes
     * through here; {@link #publishWatch} deliberately does not.
     */
    @SneakyThrows
    private void confirm(ProducerRecord<String, String> record) {
        try {
            kafkaTemplate.send(record).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing to " + record.topic(), e);
        }
    }
}
