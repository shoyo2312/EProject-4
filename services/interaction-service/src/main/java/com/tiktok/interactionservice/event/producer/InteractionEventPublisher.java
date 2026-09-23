package com.tiktok.interactionservice.event.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.event.interaction.CommentLikeChangedEvent;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.event.interaction.VideoRepostedEvent;
import com.tiktok.event.interaction.VideoSavedEvent;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.event.interaction.VideoViewedEvent;
import com.tiktok.event.interaction.VideoWatchEvent;
import com.tiktok.interactionservice.exception.EventNotPublishedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.SneakyThrows;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 *
 * <p>Undoing the write is only right if the event is really not coming, so "failed" here means
 * the producer's own verdict, never the request running out of patience first. The producer keeps
 * retrying a record until its delivery.timeout.ms is up, so that timeout is bounded in
 * application.yml to fit inside a request, and the publisher waits for it rather than for a clock
 * of its own. One case stays open, as it does for any producer without transactions: a request the
 * broker applied but whose answer never arrived is reported as a failure too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionEventPublisher {

    /**
     * Only a backstop, kept above the producer's delivery.timeout.ms (application.yml) on purpose.
     * This used to be the wait itself, at 5s against the producer's default of two minutes: a
     * request that stopped waiting had not seen a failure, only given up on a record still on its
     * way. It undid a like that the broker then took a few seconds later, and video-service
     * counted it, recommendation-service scored it and notification-service announced it.
     *
     * <p>The producer promises an answer within its delivery timeout, so this should never fire.
     * If it does, nobody knows whether the record landed; it is treated as a failure, as before.
     */
    static final Duration ACK_BACKSTOP = Duration.ofSeconds(10);

    private static final String LIKE_TOPIC = "interaction.like-events";
    private static final String COMMENT_TOPIC = "interaction.comment-events";
    private static final String COMMENT_LIKE_TOPIC = "interaction.comment-like-events";
    private static final String SHARE_TOPIC = "interaction.share-events";
    private static final String REPOST_TOPIC = "interaction.repost-events";
    private static final String SAVE_TOPIC = "interaction.save-events";
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
    public void publishCommentCreated(Long commentId, Long videoId, Long userId, String content,
                                      Long parentId, Long replyToUserId) {
        CommentCreatedEvent event = CommentCreatedEvent.of(commentId, videoId, userId, content,
                parentId, replyToUserId);
        confirm(commentRecord(videoId, "CommentCreatedEvent", objectMapper.writeValueAsString(event)));
    }

    @SneakyThrows
    public void publishCommentDeleted(Long commentId, Long videoId, Long userId) {
        CommentDeletedEvent event = CommentDeletedEvent.of(commentId, videoId, userId);
        confirm(commentRecord(videoId, "CommentDeletedEvent", objectMapper.writeValueAsString(event)));
    }

    /**
     * Not confirmed, unlike the counter-bearing sends above: nothing downstream derives a stored
     * number from this topic. The tally already lives in Cassandra and rides back on the HTTP
     * response, so a record the broker refuses costs other viewers a stale like count until their
     * next fetch — not a permanent divergence worth failing the request over. The failure is
     * logged rather than swallowed, same shape as {@link #publishWatch}.
     */
    @SneakyThrows
    public void publishCommentLikeChanged(Long commentId, Long videoId, Long userId,
                                          boolean liked, int likeCount) {
        CommentLikeChangedEvent event =
                CommentLikeChangedEvent.of(commentId, videoId, userId, liked, likeCount);
        kafkaTemplate.send(new ProducerRecord<>(COMMENT_LIKE_TOPIC, String.valueOf(videoId),
                        objectMapper.writeValueAsString(event)))
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.warn("Like change on comment {} of video {} was not accepted by the "
                                + "broker; other viewers keep a stale tally: {}",
                                commentId, videoId, failure.getMessage());
                    }
                });
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

    /** Confirmed like the rest, because it moves repost_count. */
    @SneakyThrows
    public void publishRepost(Long videoId, Long userId, boolean reposted) {
        VideoRepostedEvent event = VideoRepostedEvent.of(videoId, userId, reposted);
        confirm(new ProducerRecord<>(
                REPOST_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event)));
    }

    /** Confirmed like the rest, because it moves save_count. */
    @SneakyThrows
    public void publishSave(Long videoId, Long userId, boolean saved) {
        VideoSavedEvent event = VideoSavedEvent.of(videoId, userId, saved);
        confirm(new ProducerRecord<>(
                SAVE_TOPIC, String.valueOf(videoId), objectMapper.writeValueAsString(event)));
    }

    /**
     * Sends and waits for the broker to say it has the record. Everything that moves a counter goes
     * through here; {@link #publishWatch} deliberately does not.
     *
     * <p>Every way of not getting that answer leaves as {@link EventNotPublishedException}, which
     * is unchecked. The broker refusing a record fails the future rather than send(), and that
     * used to escape as the checked ExecutionException — past every caller's undo, which catches
     * RuntimeException. The interaction stayed stored with no event behind it, and the client's
     * retry found it already there, so nothing ever sent one.
     */
    private void confirm(ProducerRecord<String, String> record) {
        try {
            kafkaTemplate.send(record).get(ACK_BACKSTOP.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw notPublished(record, e.getCause());
        } catch (TimeoutException e) {
            // Past the producer's own delivery timeout — see ACK_BACKSTOP for what that leaves.
            throw notPublished(record, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw notPublished(record, e);
        } catch (RuntimeException e) {
            throw notPublished(record, e);
        }
    }

    private static EventNotPublishedException notPublished(ProducerRecord<String, String> record, Throwable cause) {
        log.warn("The broker did not take a record to {} with key {}; the interaction is undone",
                record.topic(), record.key(), cause);
        return new EventNotPublishedException(cause);
    }
}
