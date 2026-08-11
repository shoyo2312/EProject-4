package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
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
