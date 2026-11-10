package com.tiktok.storyservice.event.consumer;

import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.storyservice.entity.OwnerBan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Bans and unbans from admin.moderation-events, recorded in {@link OwnerBan} so every read can
 * hide a banned owner's stories. The topic carries several event types with no type field in the
 * payload, so routing is on the {@code eventType} header and nothing else; every other type is
 * another service's business.
 *
 * <p>No claim on the eventId: each event is one assignment, so a redelivery only repeats it, and
 * the topic is keyed by user, so a ban and its unban arrive in order. The guard is for a decision
 * delivered late — a replay off the DLT. Same reasoning, same upsert, as video-service's copy.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminModerationEventConsumer {

    private static final String USER_BANNED = "UserBannedEvent";
    private static final String USER_UNBANNED = "UserUnbannedEvent";

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "admin.moderation-events", groupId = "story-service")
    public void onMessage(String payload, @Header(name = "eventType", required = false) byte[] eventTypeHeader) {
        String eventType = eventTypeHeader == null ? null : new String(eventTypeHeader);

        if (USER_BANNED.equals(eventType)) {
            UserBannedEvent event = objectMapper.readValue(payload, UserBannedEvent.class);
            recordBan(event.userId(), event.bannedUntil() == null ? OwnerBan.PERMANENT : event.bannedUntil(),
                    event.occurredAt());
        } else if (USER_UNBANNED.equals(eventType)) {
            UserUnbannedEvent event = objectMapper.readValue(payload, UserUnbannedEvent.class);
            recordBan(event.userId(), null, event.occurredAt());
        } else if (eventType == null) {
            log.warn("Moderation event without an eventType header, dropped: {}", payload);
        } else {
            log.debug("Ignoring moderation eventType={}", eventType);
        }
    }

    /**
     * The upsert matches only a document no newer than the event, so a newer one makes it insert a
     * second document with the same _id, which the key refuses. Tried twice, because a user's first
     * two decisions can race with no document yet and the loser is refused either way.
     */
    private void recordBan(Long userId, Instant bannedUntil, Instant decidedAt) {
        if (!tryRecordBan(userId, bannedUntil, decidedAt) && !tryRecordBan(userId, bannedUntil, decidedAt)) {
            log.info("Ban decision for user {} from {} is older than the one recorded; ignoring it", userId, decidedAt);
        }
    }

    private boolean tryRecordBan(Long userId, Instant bannedUntil, Instant decidedAt) {
        try {
            mongoTemplate.upsert(
                    // A document the owner's closure created has no changedAt and must still match:
                    // lte alone misses a missing field, and the upsert would collide on _id for good.
                    Query.query(where("_id").is(userId).orOperator(
                            where("changedAt").is(null), where("changedAt").lte(decidedAt))),
                    new Update().set("bannedUntil", bannedUntil).set("changedAt", decidedAt),
                    OwnerBan.class);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
