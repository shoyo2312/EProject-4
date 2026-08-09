package com.tiktok.videoservice.controller;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A client asking for size=1000 must be clamped to spring.data.web.pageable.max-page-size
 * (video-service/application.yml), not fed however many rows Mongo happens to return.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class VideoControllerPageSizeTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VideoRepository videoRepository;

    @BeforeEach
    void seedVideos() {
        videoRepository.deleteAll();
        for (int i = 0; i < 60; i++) {
            videoRepository.save(Video.builder()
                    .id(Video.newId())
                    .userId(1L)
                    .title("video-" + i)
                    .rawFileUrl("s3://video-media/raw/" + i + ".mp4")
                    .visibility(VideoVisibility.PUBLIC)
                    .status(VideoStatus.PUBLISHED)
                    .build());
        }
    }

    @Test
    void getFeed_requestedSizeAboveMax_isClampedTo50() throws Exception {
        mockMvc.perform(get("/api/v1/videos/feed").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(50))
                // Nested under "page" because the app serializes with VIA_DTO, same as
                // user-service. A flat totalElements here would mean that setting was lost.
                .andExpect(jsonPath("$.data.page.totalElements").value(60))
                .andExpect(jsonPath("$.data.page.size").value(50));
    }
}
