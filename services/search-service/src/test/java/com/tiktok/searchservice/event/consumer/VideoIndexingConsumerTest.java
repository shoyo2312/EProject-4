package com.tiktok.searchservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private VideoPublishedEventConsumer videoPublishedEventConsumer;

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
        VideoPublishedEvent published = VideoPublishedEvent.of("v1", 1L, "My first video", "s3://raw/1.mp4");
        videoPublishedEventConsumer.onMessage(objectMapper.writeValueAsString(published));

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
        VideoPublishedEvent published = VideoPublishedEvent.of("v2", 1L, "title", "s3://raw/2.mp4");
        String payload = objectMapper.writeValueAsString(published);

        videoPublishedEventConsumer.onMessage(payload);
        videoPublishedEventConsumer.onMessage(payload);

        assertThat(processedEventRepository.count()).isEqualTo(1);
    }
}
