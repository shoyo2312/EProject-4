package com.tiktok.interactionservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.event.interaction.VideoViewedEvent;
import com.tiktok.event.interaction.VideoWatchEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Publishes inline right after the corresponding Cassandra write succeeds — there is no
 * outbox here (Cassandra has no cross-table transaction to pair one with), so a Kafka send
 * failure after a successful Cassandra write silently drops the event. Accepted for v1
 * since nothing currently consumes these events; revisit before any consumer depends on
 * them for correctness-critical behavior.
 */
@Component
@RequiredArgsConstructor
public class InteractionEventPublisher {

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
        kafkaTemplate.send(LIKE_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event));
    }

    /**
     * {@code interaction.comment-events} now carries two shapes — a creation and a deletion —
     * so every record leaves here with an {@code eventType} header, same reasoning as
     * video-service's VideoEventPublisher on video.video-events.
     */
    @SneakyThrows
    public void publishCommentCreated(Long commentId, Long videoId, Long userId, String content) {
        CommentCreatedEvent event = CommentCreatedEvent.of(commentId, videoId, userId, content);
        kafkaTemplate.send(commentRecord(videoId, "CommentCreatedEvent", objectMapper.writeValueAsString(event)));
    }

    @SneakyThrows
    public void publishCommentDeleted(Long commentId, Long videoId, Long userId) {
        CommentDeletedEvent event = CommentDeletedEvent.of(commentId, videoId, userId);
        kafkaTemplate.send(commentRecord(videoId, "CommentDeletedEvent", objectMapper.writeValueAsString(event)));
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
        kafkaTemplate.send(VIEW_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event));
    }

    /**
     * Every session, unlike {@link #publishView} — see ViewService.recordWatch for why the label
     * stream and the counter stream cannot be the same one. Keyed by video so one video's
     * sessions stay ordered within a partition, matching the other four topics.
     */
    @SneakyThrows
    public void publishWatch(Long videoId, Long userId, long watchedMs, long durationMs, boolean completed) {
        VideoWatchEvent event = VideoWatchEvent.of(videoId, userId, watchedMs, durationMs, completed);
        kafkaTemplate.send(WATCH_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event));
    }

    @SneakyThrows
    public void publishShare(Long shareId, Long videoId, Long userId) {
        VideoSharedEvent event = VideoSharedEvent.of(shareId, videoId, userId);
        kafkaTemplate.send(SHARE_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event));
    }
}
