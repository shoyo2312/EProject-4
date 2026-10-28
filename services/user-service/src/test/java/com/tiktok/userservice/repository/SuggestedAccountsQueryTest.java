package com.tiktok.userservice.repository;

import com.tiktok.userservice.dto.response.SuggestedAccountResponse;
import com.tiktok.userservice.entity.FollowRequest;
import com.tiktok.userservice.entity.UserBlock;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.entity.UserMute;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.service.SuggestedAccountService;
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
import java.util.List;
import java.util.Map;

import static com.tiktok.userservice.dto.response.SuggestedAccountResponse.Reason.FOLLOWED_BY;
import static com.tiktok.userservice.dto.response.SuggestedAccountResponse.Reason.FOLLOWS_YOU;
import static com.tiktok.userservice.dto.response.SuggestedAccountResponse.Reason.POPULAR;
import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Who the viewer may be offered, against a real Postgres: every exclusion is a clause of the
 * native queries, and a mocked repository would only prove the service calls them.
 *
 * <p>Rows go in through the repositories, not the services: a block placed through BlockService
 * also ends the follows, which would hide whether the queries filter blocks at all. Each
 * relationship row is saved on its own, so {@code created_at} and the Snowflake {@code id} follow
 * insertion order.
 *
 * <pre>
 * Viewer V = 1. Profiles 1..20 and 30, displayName "user{id}". followerCount is 0 except
 * 17:900, 15:800, 18:700, 19:600, 20:500. Deactivated: 7, 12.
 * Follows into V (in this order): 4, 2, 3, 5, 6, 7, 8, 10 — then 9, soft-deleted.
 * V follows (in this order):      3, 11, 12.
 * Other follows:                  3→13, 11→13, 3→14, 11→2, 12→15; 11→16 soft-deleted.
 * Blocks: V blocks 5; 6 blocks V.   Mute: V mutes 8.   Live follow request: V → 10.
 * </pre>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class SuggestedAccountsQueryTest {

    private static final long VIEWER = 1L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private SuggestedAccountService suggestedAccountService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private UserMuteRepository userMuteRepository;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @BeforeEach
    void seed() {
        userFollowRepository.deleteAllInBatch();
        userBlockRepository.deleteAllInBatch();
        userMuteRepository.deleteAllInBatch();
        followRequestRepository.deleteAllInBatch();
        userProfileRepository.deleteAllInBatch();

        Map<Long, Long> followerCounts = Map.of(17L, 900L, 15L, 800L, 18L, 700L, 19L, 600L, 20L, 500L);
        for (long id = 1; id <= 20; id++) {
            profile(id, followerCounts.getOrDefault(id, 0L), id == 7 || id == 12);
        }
        profile(30, 0, false);

        for (long follower : new long[] {4, 2, 3, 5, 6, 7, 8, 10}) {
            userFollowRepository.save(follow(follower, VIEWER, false));
        }
        userFollowRepository.save(follow(9, VIEWER, true));

        for (long following : new long[] {3, 11, 12}) {
            userFollowRepository.save(follow(VIEWER, following, false));
        }

        userFollowRepository.save(follow(3, 13, false));
        userFollowRepository.save(follow(11, 13, false));
        userFollowRepository.save(follow(3, 14, false));
        userFollowRepository.save(follow(11, 2, false));
        userFollowRepository.save(follow(12, 15, false));
        userFollowRepository.save(follow(11, 16, true));

        userBlockRepository.save(UserBlock.builder().blockerId(VIEWER).blockedId(5L).build());
        userBlockRepository.save(UserBlock.builder().blockerId(6L).blockedId(VIEWER).build());
        userMuteRepository.save(UserMute.builder().muterId(VIEWER).mutedId(8L).build());
        followRequestRepository.save(FollowRequest.builder().requesterId(VIEWER).targetId(10L).build());
    }

    @Test
    void ordersFollowsYouThenFollowedByThenMostFollowed() {
        List<SuggestedAccountResponse> all = suggestedAccountService.suggest(VIEWER, null);

        assertThat(all).extracting(a -> a.profile().userId())
                .containsExactly(2L, 4L, 13L, 14L, 17L, 15L, 18L, 19L, 20L, 9L, 16L, 30L);
        assertThat(all).extracting(SuggestedAccountResponse::reason).containsExactly(
                FOLLOWS_YOU, FOLLOWS_YOU, FOLLOWED_BY, FOLLOWED_BY,
                POPULAR, POPULAR, POPULAR, POPULAR, POPULAR, POPULAR, POPULAR, POPULAR);
    }

    @Test
    void namesTheSourceTheViewerFollowedMostRecently() {
        Map<Long, String> followedBy = suggestedAccountService.suggest(VIEWER, null).stream()
                .filter(a -> a.followedBy() != null)
                .collect(toMap(a -> a.profile().userId(), SuggestedAccountResponse::followedBy));

        assertThat(followedBy).containsOnly(entry(13L, "user11"), entry(14L, "user3"));
    }

    /** 2 follows V and is also followed by 11, whom V follows. */
    @Test
    void eachAccountAppearsOnceUnderItsFirstTier() {
        assertThat(suggestedAccountService.suggest(VIEWER, null))
                .filteredOn(a -> a.profile().userId() == 2L)
                .singleElement().extracting(SuggestedAccountResponse::reason).isEqualTo(FOLLOWS_YOU);
    }

    /** The only route to 15 runs through 12, who closed their account. */
    @Test
    void aClosedAccountIsNeverTheReason() {
        assertThat(suggestedAccountService.suggest(VIEWER, null))
                .filteredOn(a -> a.profile().userId() == 15L)
                .singleElement().satisfies(a -> {
                    assertThat(a.reason()).isEqualTo(POPULAR);
                    assertThat(a.followedBy()).isNull();
                });
    }

    @Test
    void leavesOutEveryoneTheViewerMustNotBeOffered() {
        assertThat(suggestedAccountService.suggest(VIEWER, 50)).extracting(a -> a.profile().userId())
                .doesNotContain(1L, 3L, 5L, 6L, 7L, 8L, 10L, 11L, 12L);
    }

    @Test
    void newAccountGetsTheMostFollowedButNeverItself() {
        List<SuggestedAccountResponse> all = suggestedAccountService.suggest(30L, null);

        assertThat(all).extracting(a -> a.profile().userId())
                .startsWith(17L, 15L, 18L, 19L, 20L).doesNotContain(30L, 7L, 12L);
        assertThat(all).extracting(SuggestedAccountResponse::reason).containsOnly(POPULAR);
    }

    @Test
    void limitIsClampedToOneThroughFifty() {
        for (long id = 100; id < 160; id++) {
            profile(id, 0, false);
        }

        assertThat(suggestedAccountService.suggest(VIEWER, 3)).extracting(a -> a.profile().userId())
                .containsExactly(2L, 4L, 13L);
        assertThat(suggestedAccountService.suggest(VIEWER, 0)).hasSize(1);
        assertThat(suggestedAccountService.suggest(30L, 999)).hasSize(50);
    }

    private void profile(long id, long followerCount, boolean deactivated) {
        userProfileRepository.save(UserProfile.builder()
                .userId(id)
                .displayName("user" + id)
                .followerCount(followerCount)
                .deactivatedAt(deactivated ? Instant.now() : null)
                .build());
    }

    private static UserFollow follow(long followerId, long followingId, boolean undone) {
        return UserFollow.builder()
                .followerId(followerId)
                .followingId(followingId)
                .deletedAt(undone ? Instant.now() : null)
                .build();
    }
}
