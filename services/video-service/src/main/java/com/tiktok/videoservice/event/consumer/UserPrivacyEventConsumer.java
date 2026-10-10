package com.tiktok.videoservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserPrivacyChangedEvent;
import com.tiktok.videoservice.config.KafkaUnlimitedRetryConfig;
import com.tiktok.videoservice.entity.OwnerPrivacy;
import com.tiktok.videoservice.repository.OwnerPrivacyRepository;
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
 * Keeps video-service's copy of which owners are private, and stamps it on their videos. Three
 * steps, in this order, each safe to repeat:
 * <ol>
 *   <li>Record the flag in owner_privacy, unless a newer change is already there — and stop if so.</li>
 *   <li>Stamp every live video of the owner and queue its visibility announcement, which
 *       VideoEventPublisher sends with the audience that now follows.</li>
 *   <li>Evict those videos, so the visibility check stops reading the old flag off the cache.</li>
 * </ol>
 * Step 1 before step 2 is what VideoServiceImpl.publish relies on: it reads owner_privacy again
 * after its insert, so a video inserted in the middle of this is caught one way or the other.
 *
 * <p>After step 3 it reads owner_privacy again, and repeats steps 2 and 3 with what it finds until
 * the two agree: whoever stamps last checks last. Otherwise a run that passed step 1 on an older
 * change and then stalled could land its stamp after a newer change's, and the videos would
 * contradict owner_privacy for good.
 *
 * <p>No claim on the eventId, unlike the other consumers: every step is an assignment, so a
 * redelivery only repeats them, while a claim that outlived a crash before the stamping would make
 * the redelivery skip the event and lose the switch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserPrivacyEventConsumer {

    private final MongoTemplate mongoTemplate;
    private final OwnerPrivacyRepository ownerPrivacyRepository;
    private final VideoRepository videoRepository;
    private final VideoCache videoCache;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.privacy-events", groupId = "video-service",
            containerFactory = KafkaUnlimitedRetryConfig.UNLIMITED_RETRY_FACTORY)
    @SneakyThrows
    public void onMessage(String payload) {
        UserPrivacyChangedEvent event = objectMapper.readValue(payload, UserPrivacyChangedEvent.class);

        apply(event);
    }

    private void apply(UserPrivacyChangedEvent event) {
        if (!recordUnlessNewerIsThere(event)) {
            log.info("Privacy change of user {} from {} is older than the one recorded; ignoring it",
                    event.userId(), event.occurredAt());
            return;
        }

        boolean stamped;
        boolean recorded = event.privateAccount();
        do {
            stamped = recorded;
            List<String> videoIds = videoRepository.markOwnerPrivacy(
                    event.userId(), stamped, Instant.now());
            videoCache.evictAll(videoIds);
            recorded = ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(event.userId());
        } while (recorded != stamped);
    }

    /**
     * The upsert matches only a document no newer than the event. A newer one does not match, so
     * the upsert tries to insert a second document with the same _id and the key refuses it — which
     * is how "a newer change is already recorded" reads here.
     *
     * <p>A refusal is tried once more, because the owner's first two changes can race with no
     * document yet: both insert, and the loser is refused whether or not it is the newer one. By
     * the second try the document exists, so only a newer change in it can refuse this one.
     *
     * <p>{@code lte}, not {@code lt}: a redelivery of this same event has to get through to the
     * stamping, because its first attempt may have failed after recording the flag.
     */
    private boolean recordUnlessNewerIsThere(UserPrivacyChangedEvent event) {
        return tryRecord(event) || tryRecord(event);
    }

    private boolean tryRecord(UserPrivacyChangedEvent event) {
        try {
            mongoTemplate.upsert(
                    Query.query(where("_id").is(event.userId()).and("changedAt").lte(event.occurredAt())),
                    new Update()
                            .set("privateAccount", event.privateAccount())
                            .set("changedAt", event.occurredAt()),
                    OwnerPrivacy.class);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
