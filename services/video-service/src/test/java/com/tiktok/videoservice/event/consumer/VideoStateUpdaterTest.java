package com.tiktok.videoservice.event.consumer;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refusing a stale write is only half the fix — refusing it and giving up would lose the
 * transcode result instead of the takedown. These cover the other half: losing the race means
 * re-reading and deciding again, against the document as it actually is.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class VideoStateUpdaterTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoStateUpdater videoStateUpdater;

    @Autowired
    private VideoRepository videoRepository;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
    }

    /**
     * A takedown landing in the gap between the transcode consumer's read and its write. The
     * first attempt is refused; the second reads TAKEN_DOWN and records PUBLISHED as the state a
     * restore should return to — which is what the first attempt would have done had the document
     * looked then like it does now.
     */
    @Test
    void apply_whenATakedownLandsMidFlight_reReadsAndRecordsTheOutcomeForRestore() {
        Video video = videoRepository.save(processingVideo());
        AtomicBoolean moderatorHasActed = new AtomicBoolean(false);

        BiPredicate<Video, VideoStatus> writeWithATakedownInTheGap = (candidate, expectedStatus) -> {
            if (moderatorHasActed.compareAndSet(false, true)) {
                Video moderatorsCopy = videoRepository.findByIdAndDeletedAtIsNull(video.getId()).orElseThrow();
                moderatorsCopy.markTakenDown();
                videoRepository.updateStatus(moderatorsCopy, VideoStatus.PROCESSING);
            }
            return videoRepository.updateTranscodeResult(candidate, expectedStatus);
        };

        videoStateUpdater.apply(video.getId(),
                v -> v.markPublished("http://minio/thumb.jpg", "http://minio/master.m3u8", 42),
                writeWithATakedownInTheGap,
                "VideoTranscodedEvent");

        Video after = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("the takedown must survive the transcode result that overtook it")
                .isEqualTo(VideoStatus.TAKEN_DOWN);
        assertThat(after.getStatusBeforeTakedown()).isEqualTo(VideoStatus.PUBLISHED);
        assertThat(after.getHlsUrl())
                .as("the media the transcode produced is needed the moment the video comes back")
                .isEqualTo("http://minio/master.m3u8");
    }

    @Test
    void apply_deletedVideo_writesNothing() {
        Video video = processingVideo();
        video.markDeleted();
        videoRepository.save(video);

        videoStateUpdater.apply(video.getId(), v -> v.markFailed("boom"),
                videoRepository::updateStatus, "VideoTranscodedEvent");

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getStatus())
                .isEqualTo(VideoStatus.PROCESSING);
    }

    private Video processingVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/1/t.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build();
    }
}
