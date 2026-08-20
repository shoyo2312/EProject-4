package com.tiktok.videoservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.VideoViewedEvent;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class VideoViewedEventConsumerTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoViewedEventConsumer consumer;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        videoRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    void onMessage_incrementsViewCount() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        consumer.onMessage(objectMapper.writeValueAsString(
                VideoViewedEvent.of(Long.valueOf(video.getId()), 1L)));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getViewCount()).isEqualTo(1);
    }

    /**
     * Distinct viewers each carry their own event; this side does not deduplicate, so two events
     * must produce two views. Deduplicating here as well would silently drop the second viewer.
     */
    @Test
    void onMessage_countsEachViewerSeparately() throws Exception {
        Video video = videoRepository.save(publishedVideo());

        consumer.onMessage(objectMapper.writeValueAsString(
                VideoViewedEvent.of(Long.valueOf(video.getId()), 1L)));
        consumer.onMessage(objectMapper.writeValueAsString(
                VideoViewedEvent.of(Long.valueOf(video.getId()), 2L)));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getViewCount()).isEqualTo(2);
    }

    /** Redelivery of one event, which the eventId claim is what stops — not the dedup window. */
    @Test
    void onMessage_replay_isNoOp() throws Exception {
        Video video = videoRepository.save(publishedVideo());
        String payload = objectMapper.writeValueAsString(
                VideoViewedEvent.of(Long.valueOf(video.getId()), 1L));

        consumer.onMessage(payload);
        consumer.onMessage(payload);

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getViewCount()).isEqualTo(1);
        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    @Test
    void onMessage_softDeletedVideo_leavesTheCountAlone() throws Exception {
        Video video = publishedVideo();
        video.markDeleted();
        videoRepository.save(video);

        consumer.onMessage(objectMapper.writeValueAsString(
                VideoViewedEvent.of(Long.valueOf(video.getId()), 1L)));

        assertThat(videoRepository.findById(video.getId()).orElseThrow().getViewCount()).isZero();
    }

    @Test
    void onMessage_unknownVideo_isNoOp() throws Exception {
        consumer.onMessage(objectMapper.writeValueAsString(VideoViewedEvent.of(999L, 1L)));

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    private Video publishedVideo() {
        return Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title("t")
                .rawFileUrl("s3://video-media/raw/1.mp4")
                .visibility(VideoVisibility.PUBLIC)
                .status(VideoStatus.PUBLISHED)
                .build();
    }
}
