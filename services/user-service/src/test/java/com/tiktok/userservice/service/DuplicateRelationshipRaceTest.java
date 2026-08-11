package com.tiktok.userservice.service;

import com.tiktok.userservice.exception.AlreadyBlockedException;
import com.tiktok.userservice.exception.AlreadyFollowingException;
import com.tiktok.userservice.exception.AlreadyMutedException;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserMuteRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

/**
 * The "already following / blocked / muted" checks are check-then-act: two concurrent requests for
 * the same pair both read nothing and both go on to insert. Only the partial unique index stops
 * the second, and the DataIntegrityViolationException it raises used to escape as a 500 —
 * the same user action answered 409 when it lost by a second and 500 when it lost by a millisecond.
 *
 * <p>Rather than race real threads, which decides nothing reliably, each test forces the exact
 * state a lost race leaves behind: the row is committed, and the pre-check is stubbed to report
 * what the racing request saw a moment earlier — nothing. The insert then hits the constraint for
 * real. Against the old code every case here fails with DataIntegrityViolationException.
 *
 * <p>Not @Transactional: a constraint violation marks the surrounding transaction rollback-only,
 * so a test-managed transaction would poison the assertions that follow. The @BeforeEach wipe
 * handles isolation instead.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class DuplicateRelationshipRaceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private FollowService followService;

    @Autowired
    private BlockService blockService;

    @Autowired
    private MuteService muteService;

    @Autowired
    private UserProfileService userProfileService;

    @SpyBean
    private UserFollowRepository userFollowRepository;

    @SpyBean
    private UserBlockRepository userBlockRepository;

    @SpyBean
    private UserMuteRepository userMuteRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @BeforeEach
    void setUp() {
        userFollowRepository.deleteAll();
        userBlockRepository.deleteAll();
        userMuteRepository.deleteAll();
        userProfileRepository.deleteAll();

        userProfileService.createFromRegisteredEvent(1L, "alice");
        userProfileService.createFromRegisteredEvent(2L, "bob");
    }

    @Test
    void follow_losingADuplicateRace_reports409NotAServerError() {
        followService.follow(1L, 2L);
        doReturn(Optional.empty())
                .when(userFollowRepository)
                .findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L);

        assertThatThrownBy(() -> followService.follow(1L, 2L))
                .isInstanceOf(AlreadyFollowingException.class);
    }

    @Test
    void follow_losingADuplicateRace_doesNotDoubleCountFollowers() {
        followService.follow(1L, 2L);
        doReturn(Optional.empty())
                .when(userFollowRepository)
                .findByFollowerIdAndFollowingIdAndDeletedAtIsNull(1L, 2L);

        assertThatThrownBy(() -> followService.follow(1L, 2L))
                .isInstanceOf(AlreadyFollowingException.class);

        // The rollback matters as much as the status code: the counters are the reason the
        // duplicate must not be allowed to half-apply.
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(2L).orElseThrow().getFollowerCount())
                .isEqualTo(1);
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow().getFollowingCount())
                .isEqualTo(1);
    }

    @Test
    void block_losingADuplicateRace_reports409NotAServerError() {
        blockService.block(1L, 2L);
        doReturn(Optional.empty())
                .when(userBlockRepository)
                .findByBlockerIdAndBlockedIdAndDeletedAtIsNull(1L, 2L);

        assertThatThrownBy(() -> blockService.block(1L, 2L))
                .isInstanceOf(AlreadyBlockedException.class);
    }

    @Test
    void mute_losingADuplicateRace_reports409NotAServerError() {
        muteService.mute(1L, 2L);
        doReturn(Optional.empty())
                .when(userMuteRepository)
                .findByMuterIdAndMutedIdAndDeletedAtIsNull(1L, 2L);

        assertThatThrownBy(() -> muteService.mute(1L, 2L))
                .isInstanceOf(AlreadyMutedException.class);
    }

    @Test
    void follow_sequentialDuplicate_stillReports409ThroughThePreCheck() {
        followService.follow(1L, 2L);

        // Unstubbed: the cheap read still short-circuits, so the constraint is the backstop and
        // not the normal path.
        assertThatThrownBy(() -> followService.follow(1L, 2L))
                .isInstanceOf(AlreadyFollowingException.class);
    }
}
