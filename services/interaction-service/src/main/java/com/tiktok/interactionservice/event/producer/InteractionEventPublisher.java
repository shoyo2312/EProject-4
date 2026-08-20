package com.tiktok.interactionservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.event.interaction.VideoViewedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

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

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @SneakyThrows
    public void publishLike(Long videoId, Long userId, boolean liked) {
        VideoLikeEvent event = VideoLikeEvent.of(videoId, userId, liked);
        kafkaTemplate.send(LIKE_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event));
    }

    @SneakyThrows
    public void publishCommentCreated(Long commentId, Long videoId, Long userId, String content) {
        CommentCreatedEvent event = CommentCreatedEvent.of(commentId, videoId, userId, content);
        kafkaTemplate.send(COMMENT_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event));
    }

    /** Only for views that survived deduplication — see ViewServiceImpl. */
    @SneakyThrows
    public void publishView(Long videoId, Long userId) {
        VideoViewedEvent event = VideoViewedEvent.of(videoId, userId);
        kafkaTemplate.send(VIEW_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event));
    }

    @SneakyThrows
    public void publishShare(Long shareId, Long videoId, Long userId) {
        VideoSharedEvent event = VideoSharedEvent.of(shareId, videoId, userId);
        kafkaTemplate.send(SHARE_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event));
    }
}
