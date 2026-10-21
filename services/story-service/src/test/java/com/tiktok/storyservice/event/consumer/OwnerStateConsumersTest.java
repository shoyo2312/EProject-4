package com.tiktok.storyservice.event.consumer;

import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.event.user.AccountState;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.storyservice.entity.OwnerBan;
import com.tiktok.storyservice.repository.OwnerBanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * story-service's copies of the owner decisions every read checks, against a real Mongo: the
 * guards are upserts that rely on the _id key refusing a second document, which a mock cannot show.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@ActiveProfiles("test")
class OwnerStateConsumersTest {

    private static final Long OWNER = 5L;

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private AdminModerationEventConsumer moderationConsumer;

    @Autowired
    private AccountStateEventConsumer accountConsumer;

    @Autowired
    private OwnerBanRepository ownerBanRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        ownerBanRepository.deleteAll();
    }

    @Test
    void aBanHidesTheOwnerAndAnUnbanShowsThemAgain() {
        Instant now = Instant.now();
        ban(now);
        assertThat(hidden()).isTrue();

        unban(now.plusSeconds(1));
        assertThat(hidden()).isFalse();
    }

    @Test
    void anOlderUnbanAfterANewerBanChangesNothing() {
        Instant now = Instant.now();
        ban(now);
        unban(now.minusSeconds(60));

        assertThat(ownerBanRepository.findById(OWNER).orElseThrow().getBannedUntil()).isEqualTo(OwnerBan.PERMANENT);
    }

    /** The closure's guard is null on a document a ban created; the upsert must still match it. */
    @Test
    void anUnbanLeavesAClosedAccountHidden() {
        Instant now = Instant.now();
        ban(now);
        account(AccountState.DEACTIVATED, now.plusSeconds(1));
        unban(now.plusSeconds(2));
        assertThat(hidden()).as("closed by its owner, unbanned by an admin").isTrue();

        account(AccountState.ACTIVE, now.plusSeconds(3));
        assertThat(hidden()).isFalse();
    }

    @Test
    void anOlderReopeningArrivingLateLeavesTheAccountClosed() {
        Instant now = Instant.now();
        account(AccountState.DEACTIVATED, now);
        account(AccountState.ACTIVE, now.minusSeconds(60));

        assertThat(hidden()).isTrue();
    }

    @Test
    void otherModerationEventTypesAreIgnored() {
        moderationConsumer.onMessage("{\"eventId\":\"x\"}", "VideoTakenDownEvent".getBytes());
        moderationConsumer.onMessage("{\"eventId\":\"x\"}", null);

        assertThat(ownerBanRepository.count()).isZero();
    }

    private boolean hidden() {
        return !ownerBanRepository.findHiddenAmong(List.of(OWNER), Instant.now()).isEmpty();
    }

    private void ban(Instant at) {
        moderationConsumer.onMessage(objectMapper.writeValueAsString(
                new UserBannedEvent("b" + at.toEpochMilli(), at, OWNER, 99L, "spam", null)), "UserBannedEvent".getBytes());
    }

    private void unban(Instant at) {
        moderationConsumer.onMessage(objectMapper.writeValueAsString(
                new UserUnbannedEvent("u" + at.toEpochMilli(), at, OWNER, 99L, "appeal")), "UserUnbannedEvent".getBytes());
    }

    private void account(AccountState state, Instant at) {
        accountConsumer.onMessage(objectMapper.writeValueAsString(
                new AccountStateChangedEvent("a" + at.toEpochMilli(), at, OWNER, state, null)));
    }

}
