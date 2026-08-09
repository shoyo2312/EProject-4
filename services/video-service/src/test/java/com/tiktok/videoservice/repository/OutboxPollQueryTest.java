package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox poll is a derived query, so its whole behavior lives in the method name — a wrong
 * name still compiles and still returns rows, just the wrong ones. It is exercised against a real
 * Mongo rather than a repository mock for that reason.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class OutboxPollQueryTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoRepository videoRepository;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
    }

    @Test
    void pendingVideoIsPicked() {
        Video video = save(false);

        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .extracting(Video::getId)
                .containsExactly(video.getId());
    }

    @Test
    void deletedVideoIsSkipped() {
        save(true);

        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .as("a video deleted before the poll ran must never be announced")
                .isEmpty();
    }

    @Test
    void deletedVideoIsSkippedWhileALivePendingOneIsStillPicked() {
        save(true);
        Video live = save(false);

        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .extracting(Video::getId)
                .containsExactly(live.getId());
    }

    @Test
    void alreadyPublishedVideoIsSkipped() {
        Video video = save(false);
        video.markEventPublished();
        videoRepository.save(video);

        assertThat(videoRepository.findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .isEmpty();
    }

    private Video save(boolean deleted) {
        Video video = Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/1.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PROCESSING)
                .build();
        if (deleted) {
            video.markDeleted();
        }
        return videoRepository.save(video);
    }
}
