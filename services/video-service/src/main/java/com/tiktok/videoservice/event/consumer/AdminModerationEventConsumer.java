package com.tiktok.videoservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.event.admin.VideoPurgeRequestedEvent;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.videoservice.entity.OwnerBan;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * admin.moderation-events carries several unrelated event types (UserBanned, CommentRemoved,
 * ...) with identical-looking JSON shapes for the video ones, so the eventType Kafka header
 * (set by admin-service's OutboxPublisher) is what routing relies on instead of the payload.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String VIDEO_TAKEN_DOWN = "VideoTakenDownEvent";
    private static final String VIDEO_RESTORED = "VideoRestoredEvent";
    private static final String VIDEO_PURGE_REQUESTED = "VideoPurgeRequestedEvent";
    private static final String USER_BANNED = "UserBannedEvent";
    private static final String USER_UNBANNED = "UserUnbannedEvent";

    private final IdempotentEventProcessor idempotentEventProcessor;
    private final VideoStateUpdater videoStateUpdater;
    private final VideoRepository videoRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload,
                          @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null ? null : new String(eventTypeHeader);

        if (VIDEO_TAKEN_DOWN.equals(eventType)) {
            handleTakenDown(objectMapper.readValue(payload, VideoTakenDownEvent.class));
        } else if (VIDEO_RESTORED.equals(eventType)) {
            handleRestored(objectMapper.readValue(payload, VideoRestoredEvent.class));
        } else if (VIDEO_PURGE_REQUESTED.equals(eventType)) {
            handlePurgeRequested(objectMapper.readValue(payload, VideoPurgeRequestedEvent.class));
        } else if (USER_BANNED.equals(eventType)) {
            UserBannedEvent event = objectMapper.readValue(payload, UserBannedEvent.class);
            recordBan(event.userId(), event.bannedUntil() == null ? OwnerBan.PERMANENT : event.bannedUntil(),
                    event.occurredAt());
        } else if (USER_UNBANNED.equals(eventType)) {
            UserUnbannedEvent event = objectMapper.readValue(payload, UserUnbannedEvent.class);
            recordBan(event.userId(), null, event.occurredAt());
        } else if (eventType == null) {
            // A moderation event with no eventType header is a producer bug, not a foreign event:
            // routing here is header-only, so a takedown that arrives without one is dropped in
            // silence and the video stays up with nothing to say why. Warned rather than thrown —
            // retrying cannot add a header — so it is at least visible in the log and in metrics.
            log.warn("Moderation event without an eventType header, dropped: {}", payload);
        } else {
            // CommentRemoved, UserWarned, ... — other services' events on a shared topic.
            log.debug("Ignoring moderation eventType={}", eventType);
        }
    }

    /**
     * Soft-deleted videos are skipped and a status change landing mid-flight is re-read rather
     * than overwritten — both live in {@link VideoStateUpdater}, which the transcode consumer
     * races against on this exact field.
     */
    private void handleTakenDown(VideoTakenDownEvent event) {
        idempotentEventProcessor.runOnce(event.eventId(), VIDEO_TAKEN_DOWN, () ->
                videoStateUpdater.apply(event.videoId(), video -> video.markTakenDown(event.reason()),
                        videoRepository::updateStatus, VIDEO_TAKEN_DOWN));
    }

    private void handleRestored(VideoRestoredEvent event) {
        idempotentEventProcessor.runOnce(event.eventId(), VIDEO_RESTORED, () ->
                videoStateUpdater.apply(event.videoId(), Video::markRestored,
                        videoRepository::updateStatus, VIDEO_RESTORED));
    }

    /**
     * What every viewer-facing read checks — see {@link OwnerBan}. Recorded unless a newer decision
     * is already there, the way UserPrivacyEventConsumer records privacy: the upsert matches only a
     * document no newer than the event, so a newer one makes it insert a second document with the
     * same _id, which the key refuses. Tried twice, because a user's first two decisions can race
     * with no document yet and the loser is refused either way.
     *
     * <p>No claim on the eventId: it is one assignment, so a redelivery only repeats it, and the
     * topic is keyed by user, so a ban and its unban arrive in order. The guard is for a decision
     * delivered late — a replay off the DLT.
     */
    private void recordBan(Long userId, Instant bannedUntil, Instant decidedAt) {
        if (!tryRecordBan(userId, bannedUntil, decidedAt) && !tryRecordBan(userId, bannedUntil, decidedAt)) {
            log.info("Ban decision for user {} from {} is older than the one recorded; ignoring it",
                    userId, decidedAt);
        }
    }

    private boolean tryRecordBan(Long userId, Instant bannedUntil, Instant decidedAt) {
        try {
            mongoTemplate.upsert(
                    Query.query(where("_id").is(userId).and("changedAt").lte(decidedAt)),
                    new Update().set("bannedUntil", bannedUntil).set("changedAt", decidedAt),
                    OwnerBan.class);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** The purge itself goes out on the next early-purge poll — see VideoEventPublisher. */
    private void handlePurgeRequested(VideoPurgeRequestedEvent event) {
        idempotentEventProcessor.runOnce(event.eventId(), VIDEO_PURGE_REQUESTED, () -> {
            if (!videoRepository.requestPurge(event.videoId())) {
                log.info("Purge requested for video {}, which is not in the trash — ignored", event.videoId());
            }
        });
    }
}
