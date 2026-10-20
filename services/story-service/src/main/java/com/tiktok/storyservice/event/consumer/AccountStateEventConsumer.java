package com.tiktok.storyservice.event.consumer;

import com.tiktok.event.user.AccountState;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.storyservice.entity.OwnerBan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Hides an owner's stories while they have closed their account, in the closure's own pair of
 * fields on {@link OwnerBan}. A deleted account needs nothing more here: its stories expire within
 * a day on their own, and until then the closure hides them.
 *
 * <p>No claim on the eventId: recording the state is an assignment guarded by occurredAt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountStateEventConsumer {

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "auth.account-events", groupId = "story-service")
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
    }

    /** Same upsert-or-refuse as the ban's, on the closure's guard, which a ban's document lacks. */
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
