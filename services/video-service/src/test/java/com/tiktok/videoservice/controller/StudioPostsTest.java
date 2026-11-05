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
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void listFiltersTakeCommaSeparatedValuesAndOrderFlipsTheSort() throws Exception {
        posts(Map.of("visibility", "PUBLIC,PRIVATE", "status", "PUBLISHED"))
                .andExpect(jsonPath("$.data.content[*].id", contains("private", "old")));
        posts(Map.of("views", "UNDER_1K,OVER_100K", "sort", "VIEWS", "order", "ASC"))
                .andExpect(jsonPath("$.data.content[*].id", contains("held", "old", "private")));
    }

    /** The search becomes a Mongo regex: capped so it cannot be a long one, quoted so it cannot be one at all. */
    @Test
    void theSearchIsCappedAndTakenLiterally() throws Exception {
        mockMvc.perform(get("/api/v1/videos/me").header("Authorization", "Bearer " + token())
                        .param("q", "x".repeat(101)))
                .andExpect(status().isBadRequest());
        posts(Map.of("q", "x".repeat(100))).andExpect(jsonPath("$.data.content").isEmpty());

        save("literal", OWNER, "n.*u", null, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 0, "2026-10-06T00:00:00Z");
        // As a regex "n.*u" would also find "nấu ăn".
        posts(Map.of("q", "n.*u")).andExpect(jsonPath("$.data.content[*].id", contains("literal")));
    }

    @Test
    void theOwnerEditsTheCaptionAndTheEditIsQueuedForTheReadPaths() throws Exception {
        patch("old", "caption", "{\"title\":\"Biển Nha Trang\",\"description\":\"đẹp quá #Travel\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Biển Nha Trang"))
                .andExpect(jsonPath("$.data.tags", contains("travel")));

        Video saved = videoRepository.findById("old").orElseThrow();
        assertThat(saved.getDescription()).isEqualTo("đẹp quá #Travel");
        assertThat(saved.getCaptionEventPendingAt())
                .as("search and recommendation still hold the old caption until this is announced")
                .isNotNull();
    }

    @Test
    void onlyTheOwnerEditsOrPins() throws Exception {
        patch("theirs", "caption", "{\"title\":\"mine now\"}").andExpect(status().isForbidden());
        patch("theirs", "pin", "{\"pinned\":true}").andExpect(status().isForbidden());
    }

    @Test
    void pinnedVideosLeadTheProfileUpToThree() throws Exception {
        save("fourth", OWNER, "bốn", null, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 0, "2026-10-06T00:00:00Z");
        save("third", OWNER, "ba", null, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 0, "2026-10-07T00:00:00Z");
        save("fifth", OWNER, "năm", null, VideoStatus.PUBLISHED, VideoVisibility.PUBLIC, 0, "2026-10-08T00:00:00Z");

        patch("old", "pin", "{\"pinned\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pinnedAt").isNotEmpty());
        patch("fourth", "pin", "{\"pinned\":true}").andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/videos/users/" + OWNER).header("Authorization", "Bearer " + token()))
                .andExpect(jsonPath("$.data.content[*].id",
                        contains("fourth", "old", "fifth", "third", "private", "held")));

        patch("third", "pin", "{\"pinned\":true}").andExpect(status().isOk());
        patch("fifth", "pin", "{\"pinned\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIN_LIMIT_REACHED"));

        patch("old", "pin", "{\"pinned\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pinnedAt").doesNotExist());
        patch("fifth", "pin", "{\"pinned\":true}").andExpect(status().isOk());
    }

    /** The pin is for the profile: a video waiting for review, or a private one, shows there to nobody else. */
    @Test
    void onlyAPublishedVideoThatIsNotPrivateCanBePinned() throws Exception {
        patch("held", "pin", "{\"pinned\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_PINNABLE"));
        patch("private", "pin", "{\"pinned\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_PINNABLE"));
    }

    private ResultActions posts(Map<String, String> params) throws Exception {
        var request = get("/api/v1/videos/me").header("Authorization", "Bearer " + token());
        params.forEach(request::param);
        return mockMvc.perform(request).andExpect(status().isOk());
    }

    private ResultActions patch(String videoId, String action, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.patch("/api/v1/videos/" + videoId + "/" + action)
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String token() {
        return new JwtProvider(jwtSecret).generateToken(String.valueOf(OWNER),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
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
