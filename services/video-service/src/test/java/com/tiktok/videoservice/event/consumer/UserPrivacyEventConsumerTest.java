package com.tiktok.videoservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.UserPrivacyChangedEvent;
import com.tiktok.videoservice.entity.OwnerPrivacy;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.OwnerPrivacyRepository;
import com.tiktok.videoservice.repository.ProcessedEventRepository;
import com.tiktok.videoservice.repository.VideoRepository;
import com.tiktok.videoservice.service.VideoCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UserPrivacyEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    // Mocked because what matters here is which entries are evicted; VideoCacheTest covers Redis.
    @MockitoBean
    private VideoCache videoCache;

    // Spied so a test can land another event's writes just ahead of one of this consumer's.
    @MockitoSpyBean
    private MongoTemplate mongoTemplate;

    @Autowired
    private UserPrivacyEventConsumer consumer;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private OwnerPrivacyRepository ownerPrivacyRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
        ownerPrivacyRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    void goingPrivate_stampsEveryLiveVideo_queuesItsAnnouncement_andEvictsIt() throws Exception {
        Video first = save(1L, false);
        Video second = save(1L, false);
        Video deleted = save(1L, true);
        Video someoneElses = save(2L, false);

        consumer.onMessage(json(UserPrivacyChangedEvent.of(1L, true)));

        assertThat(ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(1L)).isTrue();
        assertThat(videoRepository.findAllById(List.of(first.getId(), second.getId())))
                .hasSize(2)
                .allSatisfy(video -> {
                    assertThat(video.isOwnerPrivate()).isTrue();
                    // The per-video outbox re-announces it with the audience that now follows.
                    assertThat(video.getVisibilityEventPendingAt()).isNotNull();
                });
        assertThat(videoRepository.findById(deleted.getId()).orElseThrow().isOwnerPrivate()).isFalse();
        assertThat(videoRepository.findById(someoneElses.getId()).orElseThrow().isOwnerPrivate()).isFalse();

        ArgumentCaptor<Collection<String>> evicted = ArgumentCaptor.forClass(Collection.class);
        verify(videoCache).evictAll(evicted.capture());
        assertThat(evicted.getValue()).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    /** An outbox re-send, older than what is recorded, must not undo the newer change. */
    @Test
    void anOlderEvent_arrivingAfterANewerOne_changesNothing() throws Exception {
        Video video = save(1L, false);
        UserPrivacyChangedEvent older = UserPrivacyChangedEvent.of(1L, true);
        UserPrivacyChangedEvent newer = new UserPrivacyChangedEvent(
                UUID.randomUUID().toString(), older.occurredAt().plusSeconds(1), 1L, false);

        consumer.onMessage(json(newer));
        consumer.onMessage(json(older));

        assertThat(ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(1L)).isFalse();
        assertThat(videoRepository.findById(video.getId()).orElseThrow().isOwnerPrivate()).isFalse();
        verify(videoCache, times(1)).evictAll(any());
    }

    /**
     * What a first attempt leaves behind when it dies between its two writes: the flag recorded,
     * the videos not stamped. A hard crash also leaves any claim on the eventId in place, with
     * nothing left to release it, so one is held here. The redelivery carries the same occurredAt
     * as what is recorded, so the guard has to let an equal one through, and nothing may skip it
     * as already seen.
     */
    @Test
    void aRedeliveryAfterAFailedFanOut_stillStampsTheVideos() throws Exception {
        Video video = save(1L, false);
        UserPrivacyChangedEvent event = UserPrivacyChangedEvent.of(1L, true);
        ownerPrivacyRepository.save(new OwnerPrivacy(1L, true, event.occurredAt()));
        processedEventRepository.tryClaim(event.eventId(), UserPrivacyChangedEvent.class.getSimpleName());

        consumer.onMessage(json(event));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().isOwnerPrivate()).isTrue();
    }

    /**
     * Two runs overlapping: an older change passes the guard and stalls before stamping, while a
     * newer one records and stamps in full. The older stamp lands last, and only a read after it
     * can see that owner_privacy says otherwise.
     */
    @Test
    void aStaleStampLandingLast_isStampedOverWithWhatIsRecorded() throws Exception {
        Video video = save(1L, false);
        UserPrivacyChangedEvent older = UserPrivacyChangedEvent.of(1L, false);
        UserPrivacyChangedEvent newer = new UserPrivacyChangedEvent(
                UUID.randomUUID().toString(), older.occurredAt().plusSeconds(1), 1L, true);
        doAnswer(staleStamp -> {
            consumer.onMessage(json(newer));
            return staleStamp.callRealMethod();
        }).doCallRealMethod()
                .when(mongoTemplate).updateMulti(any(Query.class), any(UpdateDefinition.class), eq(Video.class));

        consumer.onMessage(json(older));

        assertThat(ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(1L)).isTrue();
        assertThat(videoRepository.findById(video.getId()).orElseThrow().isOwnerPrivate()).isTrue();
    }

    /**
     * The owner's first two changes racing: both find no document and insert, and the loser's is
     * refused even when it is the newer change. Here the loser is the newer one.
     */
    @Test
    void losingTheRaceToInsertTheFirstRecord_isNotTakenForANewerChange() throws Exception {
        Video video = save(1L, false);
        UserPrivacyChangedEvent newer = UserPrivacyChangedEvent.of(1L, true);
        doAnswer(lostRace -> {
            ownerPrivacyRepository.save(new OwnerPrivacy(1L, false, newer.occurredAt().minusSeconds(1)));
            throw new DuplicateKeyException("E11000 duplicate key error collection: owner_privacy");
        }).doCallRealMethod()
                .when(mongoTemplate).upsert(any(Query.class), any(UpdateDefinition.class), eq(OwnerPrivacy.class));

        consumer.onMessage(json(newer));

        assertThat(ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(1L)).isTrue();
        assertThat(videoRepository.findById(video.getId()).orElseThrow().isOwnerPrivate()).isTrue();
    }

    @Test
    void goingPublicAgain_clearsTheFlag() throws Exception {
        Video video = save(1L, false);
        UserPrivacyChangedEvent goingPrivate = UserPrivacyChangedEvent.of(1L, true);
        consumer.onMessage(json(goingPrivate));

        consumer.onMessage(json(new UserPrivacyChangedEvent(
                UUID.randomUUID().toString(), goingPrivate.occurredAt().plusSeconds(1), 1L, false)));

        assertThat(ownerPrivacyRepository.existsByUserIdAndPrivateAccountTrue(1L)).isFalse();
        assertThat(videoRepository.findById(video.getId()).orElseThrow().isOwnerPrivate()).isFalse();
    }

    private String json(UserPrivacyChangedEvent event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    private Video save(long userId, boolean deleted) {
        String id = Video.newId();
        Video video = Video.builder()
                .id(id)
                .userId(userId)
                .title("t")
                // Distinct per video: rawFileUrl is uniquely indexed.
                .rawFileUrl("s3://video-media/raw/%d/%s.mp4".formatted(userId, id))
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
        if (deleted) {
            video.markDeleted();
        }
        return videoRepository.save(video);
    }
}
