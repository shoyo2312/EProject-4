package com.tiktok.videoservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.AccountState;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.videoservice.entity.OwnerBan;
import com.tiktok.videoservice.repository.VideoRepository;
import com.tiktok.videoservice.service.VideoCache;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Hides an owner's videos while they have closed their account, and moves them to the trash when
 * its deletion runs. The hiding goes on the owner's {@link OwnerBan} document, in its own pair of
 * fields beside the ban's.
 *
 * <p>No claim on the eventId: recording the state is an assignment guarded by occurredAt, and
 * the trash move only matches videos not already there, so a redelivery repeats nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStateEventConsumer {

    private final MongoTemplate mongoTemplate;
    private final VideoRepository videoRepository;
    private final VideoCache videoCache;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.account-events", groupId = "video-service")
    @SneakyThrows
    public void onMessage(String payload) {
        AccountStateChangedEvent event = objectMapper.readValue(payload, AccountStateChangedEvent.class);
        if (event.state() == null) {
            log.warn("AccountStateChangedEvent without a state, dropped: {}", payload);
            return;
        }

        Instant deactivatedAt = event.state() == AccountState.ACTIVE ? null : event.occurredAt();
        if (!tryRecord(event.userId(), deactivatedAt, event.occurredAt())
                && !tryRecord(event.userId(), deactivatedAt, event.occurredAt())) {
            log.info("Account state of user {} from {} is older than the one recorded; ignoring it",
                    event.userId(), event.occurredAt());
        }

        // Whatever the guard said: a deletion is final, and an older reopening cannot undo it.
        if (event.state() == AccountState.DELETED) {
            List<String> trashed = videoRepository.softDeleteAllOf(event.userId(), Instant.now());
            videoCache.evictAll(trashed);
            log.info("Moved {} video(s) of deleted user {} to the trash", trashed.size(), event.userId());
        }
    }

    /**
     * Same upsert-or-refuse as the ban's record in AdminModerationEventConsumer, on the closure's
     * own guard field, which a document created by a ban does not have yet. Tried twice for the
     * same reason: two first writes can race with no document yet.
     */
    private boolean tryRecord(Long userId, Instant deactivatedAt, Instant changedAt) {
        try {
            mongoTemplate.upsert(
                    Query.query(where("_id").is(userId).orOperator(
                            where("accountStateChangedAt").is(null),
                            where("accountStateChangedAt").lte(changedAt))),
                    new Update().set("deactivatedAt", deactivatedAt).set("accountStateChangedAt", changedAt),
                    OwnerBan.class);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
