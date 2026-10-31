package com.tiktok.userservice.repository;

import com.tiktok.userservice.dto.response.RelationshipResponse;
import com.tiktok.userservice.entity.UserBlock;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.service.FollowService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How one viewer relates to a page of accounts, against a real Postgres: every fact is a clause of a
 * query, and a mocked repository would only prove the service calls it.
 *
 * <p>Rows go in through the repositories, not FollowService / BlockService, for the reason
 * {@link StoryAudienceQueryTest} gives: a block placed through the service also ends the follows
 * between the two accounts, so the rows below could not be built that way, and a query that forgot
 * to look at blocks would still pass.
 *
 * <p>Viewer 1 (a private account) and everyone else as the viewer finds them:
 * <pre>
 *    2  public, nothing between them
 *    3  blocked by the viewer           4  blocked the viewer
 *    5  private, nothing between them
 *    6  followed by the viewer          7  followed by the viewer, who is followed back
 *    8  follows the viewer, not followed back
 *    9  private, followed by the viewer
 *   10  no account at all
 *   11  every edge undone: a block, the viewer's follow and the follow back, all soft-deleted
 *   12  private, but the profile is soft-deleted
 *   13  followed by the viewer, whose follow back was soft-deleted
 * </pre>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class RelationshipsQueryTest {

    private static final long VIEWER = 1L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private FollowService followService;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void seed() {
        userFollowRepository.deleteAllInBatch();
        userBlockRepository.deleteAllInBatch();
        userProfileRepository.deleteAllInBatch();

        for (long id = VIEWER; id <= 9; id++) {
            userProfileRepository.save(profile(id, id == VIEWER || id == 5 || id == 9, false));
        }
        userProfileRepository.save(profile(11, false, false));
        userProfileRepository.save(profile(12, true, true));
        userProfileRepository.save(profile(13, false, false));

        userBlockRepository.save(block(VIEWER, 3, false));
        userBlockRepository.save(block(4, VIEWER, false));
        userBlockRepository.save(block(VIEWER, 11, true));

        userFollowRepository.save(follow(VIEWER, 6, false));
        userFollowRepository.save(follow(VIEWER, 7, false));
        userFollowRepository.save(follow(7, VIEWER, false));
        userFollowRepository.save(follow(8, VIEWER, false));
        userFollowRepository.save(follow(VIEWER, 9, false));
        userFollowRepository.save(follow(VIEWER, 11, true));
        userFollowRepository.save(follow(11, VIEWER, true));
        userFollowRepository.save(follow(VIEWER, 13, false));
        userFollowRepository.save(follow(13, VIEWER, true));
    }

    @Test
    void blockedByTheViewer() {
        assertThat(about(VIEWER, 3)).isEqualTo(new RelationshipResponse(3L, true, false, false, false));
    }

    @Test
    void blockingTheViewer() {
        assertThat(about(VIEWER, 4)).isEqualTo(new RelationshipResponse(4L, true, false, false, false));
    }

    @Test
    void aPrivateAccountIsReportedAsPrivate() {
        assertThat(about(VIEWER, 5)).isEqualTo(new RelationshipResponse(5L, false, true, false, false));
    }

    @Test
    void aPrivateAccountTheViewerFollowsIsPrivateAndFollowed() {
        assertThat(about(VIEWER, 9)).isEqualTo(new RelationshipResponse(9L, false, true, true, false));
    }

    @Test
    void followingAloneIsNotFriendship() {
        assertThat(about(VIEWER, 6)).isEqualTo(new RelationshipResponse(6L, false, false, true, false));
    }

    @Test
    void mutualFollowersAreFriends() {
        assertThat(about(VIEWER, 7)).isEqualTo(new RelationshipResponse(7L, false, false, true, true));
    }

    /** The edge points the other way, so the viewer neither follows them nor is their friend. */
    @Test
    void beingFollowedWithoutFollowingBackIsNeitherFollowingNorFriends() {
        assertThat(about(VIEWER, 8)).isEqualTo(new RelationshipResponse(8L, false, false, false, false));
    }

    @Test
    void anIdWithNoAccountIsAllFalseButStillAnswered() {
        assertThat(about(VIEWER, 10)).isEqualTo(new RelationshipResponse(10L, false, false, false, false));
    }

    /** Soft-deleted rows are still rows; none of them counts, and a deleted profile is not private. */
    @Test
    void undoneBlocksFollowsAndProfilesDoNotCount() {
        assertThat(about(VIEWER, 11)).isEqualTo(new RelationshipResponse(11L, false, false, false, false));
        assertThat(about(VIEWER, 12)).isEqualTo(new RelationshipResponse(12L, false, false, false, false));
    }

    /** Someone who unfollowed the viewer is still followed by them, and no longer a friend. */
    @Test
    void aFollowBackThatWasUndoneIsNotFriendship() {
        assertThat(about(VIEWER, 13)).isEqualTo(new RelationshipResponse(13L, false, false, true, false));
    }

    /** A guest has no blocks and follows nobody; whether the account is private is all there is to learn. */
    @Test
    void anAnonymousViewerOnlyLearnsWhoIsPrivate() {
        assertThat(followService.relationships(null, List.of(3L, 5L, 6L, 7L, 8L, 9L, 10L)))
                .containsExactlyInAnyOrder(
                        new RelationshipResponse(3L, false, false, false, false),
                        new RelationshipResponse(5L, false, true, false, false),
                        new RelationshipResponse(6L, false, false, false, false),
                        new RelationshipResponse(7L, false, false, false, false),
                        new RelationshipResponse(8L, false, false, false, false),
                        new RelationshipResponse(9L, false, true, false, false),
                        new RelationshipResponse(10L, false, false, false, false));
    }

    /**
     * Even a stray self-edge cannot make the viewer their own follower, friend or blocker: the
     * service never writes one, but nothing in the schema forbids it.
     */
    @Test
    void theViewersOwnIdHasNoEdgesToItself() {
        userFollowRepository.save(follow(VIEWER, VIEWER, false));
        userBlockRepository.save(block(VIEWER, VIEWER, false));

        assertThat(about(VIEWER, VIEWER)).isEqualTo(new RelationshipResponse(VIEWER, false, true, false, false));
    }

    @Test
    void duplicateAndNullIdsAreAnsweredOncePerDistinctId() {
        assertThat(followService.relationships(VIEWER, Arrays.asList(7L, null, 3L, 7L, null, 10L)))
                .containsExactlyInAnyOrder(
                        new RelationshipResponse(7L, false, false, true, true),
                        new RelationshipResponse(3L, true, false, false, false),
                        new RelationshipResponse(10L, false, false, false, false));
    }

    /** Counted on the list as sent, as followingAmong does: duplicates are parsed and bound in full. */
    @Test
    void moreThanAHundredIdsIsRefusedBeforeTheDistinct() {
        assertThatThrownBy(() -> followService.relationships(VIEWER, Collections.nCopies(101, 3L)))
                .isInstanceOf(TooManyProfileIdsException.class);
        assertThat(followService.relationships(VIEWER, Collections.nCopies(100, 3L))).hasSize(1);
    }

    /** The reason the endpoint exists: a page of ids must not cost a query each. */
    @Test
    void aFullPageCostsAtMostFourQueries() {
        List<Long> page = LongStream.rangeClosed(2, 101).boxed().toList();
        List<RelationshipResponse> answered = new ArrayList<>();

        long statements = statementsFor(() -> answered.addAll(followService.relationships(VIEWER, page)));

        assertThat(answered).hasSize(100);
        assertThat(statements).isLessThanOrEqualTo(4);
    }

    @Test
    void anAnonymousViewerCostsOneQueryAndAnEmptyPageNone() {
        assertThat(statementsFor(() -> followService.relationships(null, List.of(2L, 5L, 7L)))).isEqualTo(1);
        assertThat(statementsFor(() -> followService.relationships(VIEWER, List.of()))).isZero();
        assertThat(statementsFor(() -> followService.relationships(VIEWER, Arrays.asList(null, null)))).isZero();
    }

    private RelationshipResponse about(Long viewerId, long userId) {
        return followService.relationships(viewerId, List.of(userId)).getFirst();
    }

    private long statementsFor(Runnable call) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        call.run();
        return statistics.getPrepareStatementCount();
    }

    private static UserProfile profile(long userId, boolean privateAccount, boolean undone) {
        return UserProfile.builder()
                .userId(userId)
                .displayName("user" + userId)
                .privateAccount(privateAccount)
                .deletedAt(undone ? Instant.now() : null)
                .build();
    }

    private static UserFollow follow(long followerId, long followingId, boolean undone) {
        return UserFollow.builder()
                .followerId(followerId)
                .followingId(followingId)
                .deletedAt(undone ? Instant.now() : null)
                .build();
    }

    private static UserBlock block(long blockerId, long blockedId, boolean undone) {
        return UserBlock.builder()
                .blockerId(blockerId)
                .blockedId(blockedId)
                .deletedAt(undone ? Instant.now() : null)
                .build();
    }
}
