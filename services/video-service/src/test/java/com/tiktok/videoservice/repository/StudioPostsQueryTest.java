package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.dto.request.StudioPostFilter;
import com.tiktok.videoservice.dto.request.StudioPostFilter.CountRange;
import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;

/**
 * The Studio Posts table combines a caption search with up to three count filters, each an $or
 * of ranges. A Criteria holds one $or, so getting the combination wrong either throws or quietly
 * drops a filter — these pin down that every filter still narrows the list.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class StudioPostsQueryTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private static final long OWNER = 1L;
    private static final PageRequest FIRST_PAGE = PageRequest.of(0, 20);

    @BeforeEach
    void seed() {
        videoRepository.deleteAll();
        save("small", 500, 10, 0, VideoVisibility.PUBLIC, 1, OWNER);
        save("medium", 5_000, 2_000, 50, VideoVisibility.PRIVATE, 2, OWNER);
        save("big", 50_000, 20_000, 1_500, VideoVisibility.PUBLIC, 3, OWNER);
        save("viral", 2_000_000, 300_000, 40_000, VideoVisibility.FRIENDS, 4, OWNER);
    }

    @Test
    void ticksOfOneRangeFilterAreAlternatives() {
        assertThat(titles(filter(List.of(CountRange.UNDER_1K, CountRange.OVER_100K), null, null, null)))
                .containsExactly("viral", "small");
    }

    @Test
    void rangeBoundsIncludeTheLowerAndExcludeTheUpper() {
        save("exactly 1K", 1_000, 0, 0, VideoVisibility.PUBLIC, 5, OWNER);
        assertThat(titles(filter(List.of(CountRange.FROM_1K_TO_10K), null, null, null)))
                .containsExactly("exactly 1K", "medium");
    }

    @Test
    void differentFiltersAllApplyTogetherWithTheSearch() {
        StudioPostFilter filter = new StudioPostFilter(null, List.of(VideoVisibility.PUBLIC, VideoVisibility.FRIENDS), "i",
                List.of(CountRange.FROM_10K_TO_100K, CountRange.OVER_100K), List.of(CountRange.OVER_100K),
                null, null, null);
        assertThat(titles(filter)).containsExactly("viral");
    }

    @Test
    void ascendingFlipsTheChosenColumn() {
        assertThat(titles(filter(null, null, StudioPostFilter.Sort.LIKES, StudioPostFilter.Order.ASC)))
                .containsExactly("small", "medium", "big", "viral");
        assertThat(titles(filter(null, null, StudioPostFilter.Sort.LIKES, null)))
                .containsExactly("viral", "big", "medium", "small");
        assertThat(titles(filter(null, null, StudioPostFilter.Sort.NEWEST, StudioPostFilter.Order.ASC)))
                .as("oldest first")
                .containsExactly("small", "medium", "big", "viral");
    }

    @Test
    void noFilterListsEveryOwnVideoNewestFirst() {
        save("someone else's", 0, 0, 0, VideoVisibility.PUBLIC, 9, 2L);
        assertThat(titles(filter(null, null, null, null)))
                .containsExactly("viral", "big", "medium", "small");
    }

    private static StudioPostFilter filter(List<CountRange> views, List<CountRange> likes,
                                           StudioPostFilter.Sort sort, StudioPostFilter.Order order) {
        return new StudioPostFilter(null, null, null, views, likes, null, sort, order);
    }

    private List<String> titles(StudioPostFilter filter) {
        return videoRepository.findStudioPosts(OWNER, filter, FIRST_PAGE).map(Video::getTitle).getContent();
    }

    /**
     * {@code minute} only sets createdAt: higher is newer. Set after the save, because auditing
     * stamps "now" on a new document whatever it carried.
     */
    private void save(String title, long views, long likes, long comments, VideoVisibility visibility,
                      int minute, long owner) {
        Video video = videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(owner)
                .title(title)
                .rawFileUrl("raw/" + title)
                .status(VideoStatus.PUBLISHED)
                .visibility(visibility)
                .tags(List.of())
                .viewCount(views)
                .likeCount(likes)
                .commentCount(comments)
                .build());
        mongoTemplate.updateFirst(Query.query(where("_id").is(video.getId())),
                Update.update("createdAt", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L)), Video.class);
    }
}
