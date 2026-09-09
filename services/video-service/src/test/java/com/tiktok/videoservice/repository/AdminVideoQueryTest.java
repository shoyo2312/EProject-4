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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin listing takes two optional filters and a substring match, which is four combinations
 * and a regex — none of it expressible as a derived query, so none of it checked by Spring Data.
 * A wrong predicate here does not fail: it quietly returns the wrong videos to a moderator.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class AdminVideoQueryTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private VideoRepository videoRepository;

    private static final PageRequest FIRST_PAGE =
            PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "createdAt"));

    @BeforeEach
    void seed() {
        videoRepository.deleteAll();
        save("Morning routine", VideoStatus.PUBLISHED, null);
        save("Late night COOKING", VideoStatus.PUBLISHED, null);
        save("Removed for spam", VideoStatus.TAKEN_DOWN, null);
        save("Still encoding", VideoStatus.PROCESSING, null);
        save("Deleted by owner", VideoStatus.PUBLISHED, Instant.now());
        save("C++ in 60 seconds", VideoStatus.PUBLISHED, null);
    }

    @Test
    void listsEveryStatusWhenNoFilterIsGiven() {
        assertThat(videoRepository.findForAdmin(null, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .as("a moderator's list is not the feed — processing and taken-down videos belong on it")
                .containsExactlyInAnyOrder("Morning routine", "Late night COOKING",
                        "Removed for spam", "Still encoding", "C++ in 60 seconds");
    }

    @Test
    void excludesVideosTheirOwnerDeleted() {
        assertThat(videoRepository.findForAdmin(null, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .doesNotContain("Deleted by owner");
    }

    @Test
    void filtersByStatusAlone() {
        assertThat(videoRepository.findForAdmin(VideoStatus.TAKEN_DOWN, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Removed for spam");
    }

    @Test
    void matchesTitleCaseInsensitivelyOnASubstring() {
        assertThat(videoRepository.findForAdmin(null, "cook", FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Late night COOKING");
    }

    /** "+" is a quantifier; unquoted, this either throws or matches something else entirely. */
    @Test
    void treatsRegexMetacharactersInTheSearchAsLiteralText() {
        assertThat(videoRepository.findForAdmin(null, "C++", FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("C++ in 60 seconds");
    }

    @Test
    void combinesBothFilters() {
        assertThat(videoRepository.findForAdmin(VideoStatus.PUBLISHED, "routine", FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Morning routine");
        assertThat(videoRepository.findForAdmin(VideoStatus.TAKEN_DOWN, "routine", FIRST_PAGE))
                .isEmpty();
    }

    @Test
    void reportsATotalThatCountsEveryMatchAndNotJustThePage() {
        assertThat(videoRepository.findForAdmin(null, null, PageRequest.of(0, 2)).getTotalElements())
                .isEqualTo(5);
    }

    private void save(String title, VideoStatus status, Instant deletedAt) {
        videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(1L)
                .title(title)
                .rawFileUrl("raw/" + title)
                .status(status)
                .visibility(VideoVisibility.PUBLIC)
                .tags(java.util.List.of())
                .createdAt(Instant.now())
                .deletedAt(deletedAt)
                .build());
    }
}
