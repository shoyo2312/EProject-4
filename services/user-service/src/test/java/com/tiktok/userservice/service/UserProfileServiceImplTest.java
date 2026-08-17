package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.InboxEventRepository;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises UserProfileServiceImpl against a real Postgres (Testcontainers), same
 * approach as auth-service's AuthServiceImplTest, so Flyway migrations and the
 * partial unique index on user_id are verified along with the business logic.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UserProfileServiceImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private UserBlockRepository userBlockRepository;

    @Autowired
    private BlockService blockService;

    @Autowired
    private InboxEventRepository inboxEventRepository;

    @BeforeEach
    void cleanUp() {
        userFollowRepository.deleteAll();
        userBlockRepository.deleteAll();
        userProfileRepository.deleteAll();
        inboxEventRepository.deleteAll();
    }

    @Test
    @Transactional
    void createFromRegisteredEvent_createsProfileWithUsernameAsDisplayName() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe");

        UserProfileResponse profile = userProfileService.getByUserId(1L, 1L);

        assertThat(profile.userId()).isEqualTo(1L);
        assertThat(profile.displayName()).isEqualTo("johndoe");
        assertThat(profile.followerCount()).isZero();
        assertThat(profile.followingCount()).isZero();
    }

    @Test
    @Transactional
    void createFromRegisteredEvent_replay_isNoOp() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe");
        userProfileService.createFromRegisteredEvent(1L, "johndoe");

        assertThat(userProfileRepository.findAll()).hasSize(1);
    }

    /**
     * The batch lookup drops what the single lookup would 404 on rather than failing the page: an
     * unknown id, and an id on either side of a block. Asserted together because dropping only one
     * of the two is the plausible regression — the block filter is a separate query from the
     * profile fetch, so losing it leaves a page that still looks correct.
     */
    @Test
    @Transactional
    void getByUserIds_dropsUnknownAndBlockedIdsInsteadOfFailingThePage() {
        userProfileService.createFromRegisteredEvent(1L, "alice");
        userProfileService.createFromRegisteredEvent(2L, "bob");
        userProfileService.createFromRegisteredEvent(3L, "carol");
        blockService.block(3L, 1L);

        List<UserProfileResponse> profiles =
                userProfileService.getByUserIds(1L, List.of(2L, 3L, 999L));

        assertThat(profiles).extracting(UserProfileResponse::userId).containsExactly(2L);
    }

    /** A block hides both sides, so which of the two pressed the button must not matter here. */
    @Test
    @Transactional
    void getByUserIds_dropsBlockedIdInEitherDirection() {
        userProfileService.createFromRegisteredEvent(1L, "alice");
        userProfileService.createFromRegisteredEvent(2L, "bob");
        blockService.block(1L, 2L);

        assertThat(userProfileService.getByUserIds(1L, List.of(2L))).isEmpty();
        assertThat(userProfileService.getByUserIds(2L, List.of(1L))).isEmpty();
    }

    @Test
    @Transactional
    void getByUserIds_answersInTheOrderAskedAndCollapsesDuplicates() {
        userProfileService.createFromRegisteredEvent(1L, "alice");
        userProfileService.createFromRegisteredEvent(2L, "bob");
        userProfileService.createFromRegisteredEvent(3L, "carol");

        List<UserProfileResponse> profiles =
                userProfileService.getByUserIds(1L, List.of(3L, 1L, 2L, 3L));

        assertThat(profiles).extracting(UserProfileResponse::userId).containsExactly(3L, 1L, 2L);
    }

    @Test
    @Transactional
    void getByUserIds_aboveTheCap_isRejected() {
        List<Long> tooMany = LongStream.rangeClosed(1, UserProfileService.MAX_BATCH_IDS + 1)
                .boxed()
                .toList();

        assertThatThrownBy(() -> userProfileService.getByUserIds(1L, tooMany))
                .isInstanceOf(TooManyProfileIdsException.class);
    }

    @Test
    @Transactional
    void getByUserId_unknownUser_throwsNotFound() {
        assertThatThrownBy(() -> userProfileService.getByUserId(1L, 999L))
                .isInstanceOf(UserProfileNotFoundException.class);
    }

    @Test
    @Transactional
    void getByUserId_viewerBlockedByTarget_throwsNotFound() {
        userProfileService.createFromRegisteredEvent(1L, "alice");
        userProfileService.createFromRegisteredEvent(2L, "bob");
        blockService.block(1L, 2L);

        // Same 404 in both directions: the block hides each side from the other, and neither
        // response reveals that a block is what happened.
        assertThatThrownBy(() -> userProfileService.getByUserId(2L, 1L))
                .isInstanceOf(UserProfileNotFoundException.class);
        assertThatThrownBy(() -> userProfileService.getByUserId(1L, 2L))
                .isInstanceOf(UserProfileNotFoundException.class);
    }

    @Test
    @Transactional
    void getByUserId_unrelatedViewer_stillSeesProfile() {
        userProfileService.createFromRegisteredEvent(1L, "alice");
        userProfileService.createFromRegisteredEvent(2L, "bob");
        userProfileService.createFromRegisteredEvent(3L, "carol");
        blockService.block(1L, 2L);

        assertThat(userProfileService.getByUserId(3L, 1L).displayName()).isEqualTo("alice");
        assertThat(userProfileService.getByUserId(3L, 2L).displayName()).isEqualTo("bob");
    }

    @Test
    @Transactional
    void updateOwnProfile_updatesDisplayNameBioAndAvatar() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe");

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("John Doe", "Hello world", "https://example.com/avatar.png"));

        assertThat(updated.displayName()).isEqualTo("John Doe");
        assertThat(updated.bio()).isEqualTo("Hello world");
        assertThat(updated.avatarUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    @Transactional
    void updateOwnProfile_withOnlyDisplayName_keepsBioAndAvatar() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe");
        userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("John Doe", "Hello world", "https://example.com/avatar.png"));

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("Johnny", null, null));

        assertThat(updated.displayName()).isEqualTo("Johnny");
        assertThat(updated.bio()).isEqualTo("Hello world");
        assertThat(updated.avatarUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    @Transactional
    void updateOwnProfile_withEmptyBio_clearsIt() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe");
        userProfileService.updateOwnProfile(1L, new UpdateProfileRequest(null, "Hello world", null));

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest(null, "", null));

        assertThat(updated.bio()).isNull();
        assertThat(updated.displayName()).isEqualTo("johndoe");
    }
}
