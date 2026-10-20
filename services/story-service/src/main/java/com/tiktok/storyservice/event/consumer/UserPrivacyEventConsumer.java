package com.tiktok.storyservice.event.consumer;

import com.tiktok.event.user.UserPrivacyChangedEvent;
import com.tiktok.storyservice.entity.OwnerPrivacy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * Keeps story-service's copy of which owners are private. Unlike video-service's, nothing is
 * stamped on the stories: reads look the flag up per request, so recording it is the whole job.
 *
 * <p>No claim on the eventId: it is one guarded assignment. The upsert matches only a document no
 * newer than the event; a newer one makes it collide on _id, which is how "a newer change is
 * already recorded" reads. Tried twice, because a user's first two changes can race with no
 * document yet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPrivacyEventConsumer {

    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.privacy-events", groupId = "story-service")
    public void onMessage(String payload) {
        UserPrivacyChangedEvent event = objectMapper.readValue(payload, UserPrivacyChangedEvent.class);
        if (!tryRecord(event) && !tryRecord(event)) {
            log.info("Privacy change of user {} from {} is older than the one recorded; ignoring it",
                    event.userId(), event.occurredAt());
        }
    }

    private boolean tryRecord(UserPrivacyChangedEvent event) {
        try {
            mongoTemplate.upsert(
                    Query.query(where("_id").is(event.userId()).and("changedAt").lte(event.occurredAt())),
                    new Update().set("privateAccount", event.privateAccount()).set("changedAt", event.occurredAt()),
                    OwnerPrivacy.class);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
