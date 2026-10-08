package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.videoservice.entity.OwnerBan;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.OwnerBanRepository;
import com.tiktok.videoservice.repository.ProcessedEventRepository;
import com.tiktok.videoservice.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AdminModerationEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private AdminModerationEventConsumer consumer;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private OwnerBanRepository ownerBanRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
        processedEventRepository.deleteAll();
        ownerBanRepository.deleteAll();
    }

    @Test
    void onMessage_takenDown_marksVideoTakenDown() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://video-media/raw/1.mp4"));

        VideoTakenDownEvent event = VideoTakenDownEvent.of(video.getId(), 99L, "nudity");
        consumer.onMessage(objectMapper.writeValueAsString(event), "VideoTakenDownEvent".getBytes());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.TAKEN_DOWN);
        assertThat(updated.getTakedownReason())
                .as("the reason is what any listing shows for why the video is down")
                .isEqualTo("nudity");
    }

    @Test
    void onMessage_restored_marksVideoPublished() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://video-media/raw/2.mp4"));
        video.markTakenDown("policy violation");
        videoRepository.save(video);

        VideoRestoredEvent event = VideoRestoredEvent.of(video.getId(), 99L, "appeal accepted");
        consumer.onMessage(objectMapper.writeValueAsString(event), "VideoRestoredEvent".getBytes());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
        assertThat(updated.getTakedownReason())
                .as("a restored video is not down, so nothing should still say why it was")
                .isNull();
    }

    @Test
    void onMessage_restored_returnsToStatusBeforeTakedown() throws Exception {
        Video video = videoRepository.save(processingVideo());
        video.markTakenDown("policy violation");
        videoRepository.save(video);

        VideoRestoredEvent event = VideoRestoredEvent.of(video.getId(), 99L, "appeal accepted");
        consumer.onMessage(objectMapper.writeValueAsString(event), "VideoRestoredEvent".getBytes());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("a video taken down mid-transcode has no hlsUrl, so restore must not publish it")
                .isEqualTo(VideoStatus.PROCESSING);
    }

    /** A restore for a video nobody took down leaves a video still being transcoded alone. */
    @Test
    void onMessage_restoredWithoutATakedown_doesNotPublishAVideoInThePipeline() throws Exception {
        Video video = videoRepository.save(processingVideo());

        VideoRestoredEvent event = VideoRestoredEvent.of(video.getId(), 99L, "stray");
        consumer.onMessage(objectMapper.writeValueAsString(event), "VideoRestoredEvent".getBytes());

        Video updated = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(updated.getPublishedAt()).isNull();
    }

    @Test
    void onMessage_takenDown_deletedVideo_isIgnored() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://video-media/raw/6.mp4"));
        video.markDeleted();
        videoRepository.updateSoftDeleted(video);

        VideoTakenDownEvent event = VideoTakenDownEvent.of(video.getId(), 99L, "nudity");
        consumer.onMessage(objectMapper.writeValueAsString(event), "VideoTakenDownEvent".getBytes());

        Video after = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("moderation must not write status onto a video its owner already deleted")
                .isEqualTo(VideoStatus.PUBLISHED);
        assertThat(after.getStatusBeforeTakedown()).isNull();
    }

    @Test
    void onMessage_unrelatedEventType_isIgnored() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://video-media/raw/3.mp4"));

        consumer.onMessage("{\"videoId\":\"v\",\"commentId\":1}", "CommentRemovedEvent".getBytes());

        Video unchanged = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(VideoStatus.PUBLISHED);
    }

    /**
     * A ban used to stop at auth-service: the account could not sign in, and its videos went on
     * playing everywhere. What is recorded here is what every viewer-facing read checks.
     */
    @Test
    void onMessage_userBanned_recordsTheBan_andAnUnbanLiftsIt() throws Exception {
        Instant until = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        consumer.onMessage(objectMapper.writeValueAsString(UserBannedEvent.of(5L, 99L, "spam", until)),
                "UserBannedEvent".getBytes());

        assertThat(ownerBanRepository.findById(5L).orElseThrow().getBannedUntil()).isEqualTo(until);

        consumer.onMessage(objectMapper.writeValueAsString(UserUnbannedEvent.of(5L, 99L, "appeal")),
                "UserUnbannedEvent".getBytes());

        assertThat(ownerBanRepository.findById(5L).orElseThrow().getBannedUntil()).isNull();
    }

    /** No end is a ban for good, stored as a date every read compares past now. */
    @Test
    void onMessage_permanentBan_isRecordedAsOneThatNeverEnds() throws Exception {
        consumer.onMessage(objectMapper.writeValueAsString(UserBannedEvent.of(6L, 99L, "spam")),
                "UserBannedEvent".getBytes());

        assertThat(ownerBanRepository.findById(6L).orElseThrow().getBannedUntil()).isEqualTo(OwnerBan.PERMANENT);
    }

    /** An older decision delivered late — a replay off the DLT — does not undo a newer one. */
    @Test
    void onMessage_anOlderUnbanAfterANewerBan_changesNothing() throws Exception {
        Instant now = Instant.now();
        UserUnbannedEvent older = new UserUnbannedEvent("e1", now.minusSeconds(60), 7L, 99L, "appeal");
        UserBannedEvent newer = new UserBannedEvent("e2", now, 7L, 99L, "again", null);

        consumer.onMessage(objectMapper.writeValueAsString(newer), "UserBannedEvent".getBytes());
        consumer.onMessage(objectMapper.writeValueAsString(older), "UserUnbannedEvent".getBytes());

        assertThat(ownerBanRepository.findById(7L).orElseThrow().getBannedUntil()).isEqualTo(OwnerBan.PERMANENT);
    }

    @Test
    void onMessage_replay_isNoOp() throws Exception {
        Video video = videoRepository.save(publishedVideo("s3://video-media/raw/4.mp4"));

        VideoTakenDownEvent event = VideoTakenDownEvent.of(video.getId(), 99L, "spam");
        String payload = objectMapper.writeValueAsString(event);
        byte[] header = "VideoTakenDownEvent".getBytes();

        consumer.onMessage(payload, header);
        consumer.onMessage(payload, header);

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    private Video processingVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/5.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build();
    }

    private Video publishedVideo(String rawFileUrl) {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl(rawFileUrl)
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
    }
}
