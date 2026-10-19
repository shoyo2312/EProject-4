package com.tiktok.videoservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.event.user.AccountState;
import com.tiktok.event.user.AccountStateChangedEvent;
import com.tiktok.videoservice.entity.OwnerBan;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.OwnerBanRepository;
import com.tiktok.videoservice.repository.VideoRepository;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AccountStateEventConsumerTest {

    private static final Long OWNER = 5L;

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private AccountStateEventConsumer consumer;

    @Autowired
    private AdminModerationEventConsumer moderationConsumer;

    @Autowired
    private OwnerBanRepository ownerBanRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        ownerBanRepository.deleteAll();
        videoRepository.deleteAll();
    }

    @Test
    void closing_hidesTheOwner_andReopeningShowsThemAgain() throws Exception {
        Instant now = Instant.now();
        send(new AccountStateChangedEvent("e1", now, OWNER, AccountState.DEACTIVATED, null));
        assertThat(hidden()).isTrue();

        send(new AccountStateChangedEvent("e2", now.plusSeconds(1), OWNER, AccountState.ACTIVE, null));
        assertThat(hidden()).isFalse();
    }

    @Test
    void anOlderReopeningArrivingLate_leavesTheAccountClosed() throws Exception {
        Instant now = Instant.now();
        send(new AccountStateChangedEvent("e2", now, OWNER, AccountState.DEACTIVATED, null));
        send(new AccountStateChangedEvent("e1", now.minusSeconds(60), OWNER, AccountState.ACTIVE, null));

        assertThat(hidden()).isTrue();
    }

    /** The two decisions are independent: neither lifting one ends the other. */
    @Test
    void anUnban_leavesAClosedAccountHidden_andAReopening_leavesABanInForce() throws Exception {
        Instant now = Instant.now();
        send(new AccountStateChangedEvent("e1", now, OWNER, AccountState.DEACTIVATED, null));
        ban(now.plusSeconds(1));
        unban(now.plusSeconds(2));
        assertThat(hidden()).as("closed by its owner, unbanned by an admin").isTrue();

        ban(now.plusSeconds(3));
        send(new AccountStateChangedEvent("e2", now.plusSeconds(4), OWNER, AccountState.ACTIVE, null));
        assertThat(hidden()).as("reopened by its owner, still banned").isTrue();

        unban(now.plusSeconds(5));
        assertThat(hidden()).isFalse();
    }

    @Test
    void aBanRecordedFirst_isNotLostWhenTheAccountCloses() throws Exception {
        Instant now = Instant.now();
        ban(now);
        send(new AccountStateChangedEvent("e1", now.plusSeconds(1), OWNER, AccountState.DEACTIVATED, null));

        OwnerBan record = ownerBanRepository.findById(OWNER).orElseThrow();
        assertThat(record.getBannedUntil()).isEqualTo(OwnerBan.PERMANENT);
        assertThat(record.getDeactivatedAt()).isNotNull();
    }

    @Test
    void deletion_movesEveryVideoToTheTrash_andIsSafeToRepeat() throws Exception {
        Video first = videoRepository.save(publishedVideo());
        Video second = videoRepository.save(publishedVideo());
        AccountStateChangedEvent deleted =
                new AccountStateChangedEvent("e1", Instant.now(), OWNER, AccountState.DELETED, null);

        send(deleted);
        Instant trashedAt = videoRepository.findById(first.getId()).orElseThrow().getDeletedAt();
        send(deleted);

        assertThat(hidden()).isTrue();
        assertThat(trashedAt).isNotNull();
        assertThat(videoRepository.findById(first.getId()).orElseThrow().getDeletedAt())
                .as("a redelivery does not move the video again").isEqualTo(trashedAt);
        assertThat(videoRepository.findById(second.getId()).orElseThrow().isDeleted()).isTrue();
    }

    private boolean hidden() {
        return !ownerBanRepository.findHiddenAmong(List.of(OWNER), Instant.now()).isEmpty();
    }

    private void send(AccountStateChangedEvent event) throws Exception {
        consumer.onMessage(objectMapper.writeValueAsString(event));
    }

    private void ban(Instant at) throws Exception {
        moderationConsumer.onMessage(objectMapper.writeValueAsString(
                new UserBannedEvent("b" + at.toEpochMilli(), at, OWNER, 99L, "spam", null)),
                "UserBannedEvent".getBytes());
    }

    private void unban(Instant at) throws Exception {
        moderationConsumer.onMessage(objectMapper.writeValueAsString(
                new UserUnbannedEvent("u" + at.toEpochMilli(), at, OWNER, 99L, "appeal")),
                "UserUnbannedEvent".getBytes());
    }

    private Video publishedVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(OWNER)
                .title("t")
                .rawFileUrl("s3://video-media/raw/" + Video.newId() + ".mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
    }
}
