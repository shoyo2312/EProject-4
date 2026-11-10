package com.tiktok.userservice.repository;

import com.tiktok.userservice.dto.request.StoryAudienceScope;
import com.tiktok.userservice.dto.response.StoryAudienceResponse;
import com.tiktok.userservice.entity.UserBlock;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.entity.UserMute;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.service.FollowService;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a new story is announced to, against a real Postgres: every exclusion is a clause of one
 * JPQL query, and a mocked repository would only prove the service calls it.
 *
 * <p>Rows go in through the repositories, not FollowService / BlockService. A block placed through
 * the service also ends the follows between the two accounts, which would leave the follower out of
 * the audience for the wrong reason and hide whether the query filters blocks at all.
 *
 * <p>Owner 1 is followed by 2..9:
 * <pre>
 *   2  plain - the owner once followed back and once blocked them, both since undone
 *   3  followed back by the owner
 *   4  blocked by the owner            5  blocked the owner
 *   6  deactivated profile
 *   7  muted the owner                 8  muted the owner, then un-muted
 *   9  unfollowed (soft-deleted follow)
 * </pre>
 * Undone means soft-deleted: a row that is still there but no longer counts.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class StoryAudienceQueryTest {

    private static final long OWNER = 1L;

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
    private UserMuteRepository userMuteRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @BeforeEach
    void seed() {
        userFollowRepository.deleteAllInBatch();
        userBlockRepository.deleteAllInBatch();
        userMuteRepository.deleteAllInBatch();
        userProfileRepository.deleteAllInBatch();

        for (long id = OWNER; id <= 9; id++) {
            userProfileRepository.save(UserProfile.builder()
                    .userId(id)
                    .displayName("user" + id)
                    .deactivatedAt(id == 6 ? Instant.now() : null)
                    .build());
        }

        // Highest id first: rows come back in storage order unless the query sorts them, and paging
        // by follower id means nothing if they do not arrive ascending.
        for (long follower = 9; follower >= 2; follower--) {
            userFollowRepository.save(follow(follower, OWNER, follower == 9));
        }
        userFollowRepository.save(follow(OWNER, 3, false));
        userFollowRepository.save(follow(OWNER, 2, true));

        userBlockRepository.save(block(OWNER, 4, false));
        userBlockRepository.save(block(5, OWNER, false));
        userBlockRepository.save(block(OWNER, 2, true));

        userMuteRepository.save(mute(7, OWNER, false));
        userMuteRepository.save(mute(8, OWNER, true));
    }

    @Test
    void followersScopeIsEveryFollowerWhoMaySee() {
        StoryAudienceResponse page = followService.storyAudience(OWNER, StoryAudienceScope.FOLLOWERS, null, null);

        assertThat(page.userIds()).containsExactly(2L, 3L, 8L);
        assertThat(page.next()).isNull();
    }

    @Test
    void friendsScopeIsOnlyFollowersFollowedBack() {
        StoryAudienceResponse page = followService.storyAudience(OWNER, StoryAudienceScope.FRIENDS, null, null);

        assertThat(page.userIds()).containsExactly(3L);
        assertThat(page.next()).isNull();
    }

    @Test
    void pagesByFollowerId() {
        StoryAudienceResponse first = followService.storyAudience(OWNER, StoryAudienceScope.FOLLOWERS, null, 2);
        assertThat(first.userIds()).containsExactly(2L, 3L);
        assertThat(first.next()).isEqualTo(3L);

        StoryAudienceResponse second =
                followService.storyAudience(OWNER, StoryAudienceScope.FOLLOWERS, first.next(), 2);
        assertThat(second.userIds()).containsExactly(8L);
        assertThat(second.next()).isNull();
    }

    /** Below one is raised (a zero-size page is an error in Spring Data), above 500 is cut. */
    @Test
    void sizeIsClampedToOneThroughFiveHundred() {
        assertThat(followService.storyAudience(OWNER, StoryAudienceScope.FOLLOWERS, null, 0).userIds())
                .containsExactly(2L);

        List<UserFollow> crowd = new ArrayList<>();
        for (long follower = 100; follower < 700; follower++) {
            crowd.add(follow(follower, OWNER, false));
        }
        userFollowRepository.saveAll(crowd);

        StoryAudienceResponse page =
                followService.storyAudience(OWNER, StoryAudienceScope.FOLLOWERS, null, 10_000);
        assertThat(page.userIds()).hasSize(500);
        assertThat(page.next()).isEqualTo(page.userIds().getLast());
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

    private static UserMute mute(long muterId, long mutedId, boolean undone) {
        return UserMute.builder()
                .muterId(muterId)
                .mutedId(mutedId)
                .deletedAt(undone ? Instant.now() : null)
                .build();
    }
}
