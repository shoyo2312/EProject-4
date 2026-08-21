package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.searchservice.document.VideoDocument;
import com.tiktok.searchservice.repository.ProcessedEventRepository;
import com.tiktok.searchservice.repository.VideoDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class VideoIndexingConsumerTest {

    @Container
    @ServiceConnection
    static ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.14.0"))
            .withEnv("xpack.security.enabled", "false");

    @Autowired
    private VideoEventConsumer videoEventConsumer;

    @Autowired
    private VideoTranscodedEventConsumer videoTranscodedEventConsumer;

    @Autowired
    private VideoDocumentRepository videoDocumentRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        videoDocumentRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    @Test
    void onMessage_indexesVideoThenAppliesTranscoding() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("v1", 1L, "My first video", "s3://raw/1.mp4", List.of());
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));

        VideoDocument indexed = videoDocumentRepository.findById("v1").orElseThrow();
        assertThat(indexed.getTitle()).isEqualTo("My first video");
        assertThat(indexed.getStatus()).isEqualTo("PROCESSING");

        VideoTranscodedEvent transcoded = VideoTranscodedEvent.success("v1", "http://minio/thumb.jpg", "http://minio/master.m3u8", 42);
        videoTranscodedEventConsumer.onMessage(objectMapper.writeValueAsString(transcoded));

        VideoDocument published1 = videoDocumentRepository.findById("v1").orElseThrow();
        assertThat(published1.getStatus()).isEqualTo("PUBLISHED");
        assertThat(published1.getThumbnailUrl()).isEqualTo("http://minio/thumb.jpg");
    }

    @Test
    void onMessage_replay_isNoOp() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("v2", 1L, "title", "s3://raw/2.mp4", List.of());
        String payload = objectMapper.writeValueAsString(published);

        videoEventConsumer.onMessage(payload, header("VideoPublishedEvent"));
        videoEventConsumer.onMessage(payload, header("VideoPublishedEvent"));

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }

    /**
     * The user-visible half of the deletion event: without it a removed video keeps coming back
     * in search results, because nothing in this service ever hears that it is gone.
     */
    @Test
    void onMessage_deletion_dropsTheDocumentFromTheIndex() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("v3", 1L, "title", "s3://raw/3.mp4", List.of());
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(published), header("VideoPublishedEvent"));
        assertThat(videoDocumentRepository.findById("v3")).isPresent();

        VideoDeletedEvent deleted = VideoDeletedEvent.of("v3", 1L, "s3://raw/3.mp4");
        videoEventConsumer.onMessage(objectMapper.writeValueAsString(deleted), header("VideoDeletedEvent"));

        assertThat(videoDocumentRepository.findById("v3")).isEmpty();
    }

    /**
     * The publication and the deletion of one video must not share a processed-events id, or
     * whichever arrives second is mistaken for a replay of the first and dropped.
     */
    @Test
    void onMessage_deletion_isNotMistakenForAReplayOfThePublication() throws Exception {
        VideoPublishedEvent published = VideoPublishedEvent.of("v4", 1L, "title", "s3://raw/4.mp4", List.of());
        VideoDeletedEvent deleted = VideoDeletedEvent.of("v4", 1L, "s3://raw/4.mp4");

        assertThat(deleted.eventId()).isNotEqualTo(published.eventId());
    }

    private byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }
}
