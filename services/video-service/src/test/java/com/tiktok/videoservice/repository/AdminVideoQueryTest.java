package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.dto.response.DailyVideoStatsResponse;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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
        save("Morning routine", VideoStatus.PUBLISHED, null, 1L);
        save("Late night COOKING", VideoStatus.PUBLISHED, null, 1L);
        save("Removed for spam", VideoStatus.TAKEN_DOWN, null, 2L);
        save("Still encoding", VideoStatus.PROCESSING, null, 1L);
        save("Deleted by owner", VideoStatus.PUBLISHED, Instant.now(), 1L);
        save("C++ in 60 seconds", VideoStatus.PUBLISHED, null, 2L);
    }

    @Test
    void listsEveryStatusWhenNoFilterIsGiven() {
        assertThat(videoRepository.findForAdmin(null, null, null, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .as("a moderator's list is not the feed — processing, taken-down and deleted videos all belong on it")
                .containsExactlyInAnyOrder("Morning routine", "Late night COOKING",
                        "Removed for spam", "Still encoding", "C++ in 60 seconds", "Deleted by owner");
    }

    @Test
    void includesVideosTheirOwnerDeleted() {
        assertThat(videoRepository.findForAdmin(null, null, null, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .contains("Deleted by owner");
    }

    @Test
    void deletedTrueNarrowsToOnlyDeletedRows() {
        assertThat(videoRepository.findForAdmin(null, null, null, true, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Deleted by owner");
    }

    @Test
    void deletedFalseExcludesDeletedRows() {
        assertThat(videoRepository.findForAdmin(null, null, null, false, FIRST_PAGE))
                .extracting(Video::getTitle)
                .doesNotContain("Deleted by owner")
                .hasSize(5);
    }

    /** status still narrows within the deleted-only set — the two filters are ANDed. */
    @Test
    void combinesDeletedWithStatus() {
        assertThat(videoRepository.findForAdmin(VideoStatus.PUBLISHED, null, null, true, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Deleted by owner");
        assertThat(videoRepository.findForAdmin(VideoStatus.TAKEN_DOWN, null, null, true, FIRST_PAGE))
                .isEmpty();
    }

    @Test
    void filtersByStatusAlone() {
        assertThat(videoRepository.findForAdmin(VideoStatus.TAKEN_DOWN, null, null, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Removed for spam");
    }

    @Test
    void matchesTitleCaseInsensitivelyOnASubstring() {
        assertThat(videoRepository.findForAdmin(null, "cook", null, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Late night COOKING");
    }

    /** "+" is a quantifier; unquoted, this either throws or matches something else entirely. */
    @Test
    void treatsRegexMetacharactersInTheSearchAsLiteralText() {
        assertThat(videoRepository.findForAdmin(null, "C++", null, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("C++ in 60 seconds");
    }

    @Test
    void combinesBothFilters() {
        assertThat(videoRepository.findForAdmin(VideoStatus.PUBLISHED, "routine", null, null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Morning routine");
        assertThat(videoRepository.findForAdmin(VideoStatus.TAKEN_DOWN, "routine", null, null, FIRST_PAGE))
                .isEmpty();
    }

    @Test
    void reportsATotalThatCountsEveryMatchAndNotJustThePage() {
        assertThat(videoRepository.findForAdmin(null, null, null, null, PageRequest.of(0, 2)).getTotalElements())
                .isEqualTo(6);
    }

    /**
     * The whole point of the owner filter: a moderator types a handle, it resolves to an id
     * somewhere else, and none of these titles contain it. AND-ing the two would return nothing.
     */
    @Test
    void matchesVideosByOwnerEvenWhenTheTitleDoesNot() {
        assertThat(videoRepository.findForAdmin(null, "mihug2004", java.util.List.of(2L), null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactlyInAnyOrder("Removed for spam", "C++ in 60 seconds");
    }

    @Test
    void keepsTitleHitsFromOtherOwnersAlongsideTheOwnerHits() {
        assertThat(videoRepository.findForAdmin(null, "routine", java.util.List.of(2L), null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactlyInAnyOrder("Morning routine", "Removed for spam", "C++ in 60 seconds");
    }

    /** status still narrows — it is a filter, not part of the search term. */
    @Test
    void narrowsOwnerHitsByStatus() {
        assertThat(videoRepository.findForAdmin(VideoStatus.TAKEN_DOWN, null, java.util.List.of(2L), null, FIRST_PAGE))
                .extracting(Video::getTitle)
                .containsExactly("Removed for spam");
    }

    @Test
    void ignoresAnEmptyOwnerListRatherThanMatchingNothing() {
        assertThat(videoRepository.findForAdmin(null, null, java.util.List.of(), null, FIRST_PAGE))
                .hasSize(6);
    }

    /**
     * The listing counts deleted rows, so the series under it must too — otherwise the console
     * divides one set by another and every percentage is quietly wrong. It is also the only
     * count that holds still: excluding deletions moves a past day's figure every time an owner
     * clears out an old video.
     */
    @Test
    void dailyUploadsCountTheSameVideosTheListingDoes() {
        long listed = videoRepository.findForAdmin(null, null, null, null, FIRST_PAGE).getTotalElements();

        long counted = videoRepository.countDailyUploads(Instant.now().minus(1, ChronoUnit.DAYS))
                .stream()
                .mapToLong(DailyVideoStatsResponse::uploads)
                .sum();

        assertThat(counted).isEqualTo(listed);
        assertThat(counted).isEqualTo(6);
    }

    /** The cohort columns: of the videos uploaded that day, what is waiting and what is unwatchable. */
    @Test
    void dailyUploadsReportTheCohortStanding() {
        assertThat(videoRepository.countDailyUploads(Instant.now().minus(1, ChronoUnit.DAYS)))
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.day()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
                    assertThat(day.pendingReview()).isZero();
                    assertThat(day.notPlayable()).as("one PROCESSING video").isEqualTo(1);
                });
    }

    private void save(String title, VideoStatus status, Instant deletedAt, long userId) {
        videoRepository.save(Video.builder()
                .id(Video.newId())
                .userId(userId)
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
