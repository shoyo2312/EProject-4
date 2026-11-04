package com.tiktok.videoservice.controller;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.videoservice.client.UserSettingsClient;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TikTok Studio's Posts table: the caller's own videos, every status, filtered and sorted in Mongo. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class StudioPostsTest {

    private static final long OWNER = 7L;

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private UserSettingsClient userSettingsClient;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void seedVideos() {
        videoRepository.deleteAll();
        save("old", OWNER, "Đi biển Vũng Tàu", null, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 50, "2026-10-01T00:00:00Z");
        save("held", OWNER, "nấu ăn", "Công thức PHỞ bò", VideoStatus.PENDING_REVIEW, VideoVisibility.PUBLIC, 0, "2026-10-02T00:00:00Z");
        save("private", OWNER, "nhật ký", null, VideoStatus.PUBLISHED, VideoVisibility.PRIVATE, 900, "2026-10-03T00:00:00Z");
        save("theirs", 8L, "phở Hà Nội", null, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 5000, "2026-10-04T00:00:00Z");
        save("deleted", OWNER, "phở cũ", null, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 1, "2026-10-05T00:00:00Z");
        mongoTemplate.updateFirst(Query.query(where("_id").is("deleted")), Update.update("deletedAt", Instant.now()), Video.class);
    }

    /** 403, not 401: this service answers every missing token that way (see VideoControllerTest). */
    @Test
    void noTokenNoPosts() throws Exception {
        mockMvc.perform(get("/api/v1/videos/me")).andExpect(status().isForbidden());
    }

    @Test
    void theOwnerSeesEveryStatusButNotDeletedOrOthersNewestFirst() throws Exception {
        posts(Map.of()).andExpect(jsonPath("$.data.content[*].id", contains("private", "held", "old")));
    }

    @Test
    void filtersNarrowAndSortReorders() throws Exception {
        posts(Map.of("status", "PENDING_REVIEW")).andExpect(jsonPath("$.data.content[*].id", contains("held")));
        posts(Map.of("visibility", "PRIVATE")).andExpect(jsonPath("$.data.content[*].id", contains("private")));
        posts(Map.of("q", "phở")).andExpect(jsonPath("$.data.content[*].id", contains("held")));
        posts(Map.of("sort", "VIEWS")).andExpect(jsonPath("$.data.content[*].id", contains("private", "old", "held")));
    }

    private ResultActions posts(Map<String, String> params) throws Exception {
        String token = new JwtProvider(jwtSecret).generateToken(String.valueOf(OWNER),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
        var request = get("/api/v1/videos/me").header("Authorization", "Bearer " + token);
        params.forEach(request::param);
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    /** createdAt is set after the save: auditing stamps "now" on a new document whatever it carried. */
    private void save(String id, long owner, String title, String description, VideoStatus status,
                       VideoVisibility visibility, long views, String createdAt) {
        videoRepository.save(Video.builder()
                .id(id)
                .userId(owner)
                .title(title)
                .description(description)
                .rawFileUrl("s3://video-media/raw/" + id + ".mp4")
                .status(status)
                .visibility(visibility)
                .viewCount(views)
                .build());
        mongoTemplate.updateFirst(Query.query(where("_id").is(id)),
                Update.update("createdAt", Instant.parse(createdAt)), Video.class);
    }
}
