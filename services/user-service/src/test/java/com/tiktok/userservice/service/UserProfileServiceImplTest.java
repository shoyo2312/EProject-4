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
import org.springframework.data.domain.PageRequest;
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

    /** A picture the user uploaded themselves, which nothing downstream may replace. */
    private static final String OWN_AVATAR = "https://cdn.tiktok-clone.local/avatars/own.png";

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
    private FollowService followService;

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
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

        UserProfileResponse profile = userProfileService.getByUserId(1L, 1L);

        assertThat(profile.userId()).isEqualTo(1L);
        assertThat(profile.displayName()).isEqualTo("johndoe");
        assertThat(profile.followerCount()).isZero();
        assertThat(profile.followingCount()).isZero();
    }

    @Test
    @Transactional
    void createFromRegisteredEvent_socialSignup_keepsProviderAvatar() {
        userProfileService.createFromRegisteredEvent(
                1L, "johndoe", "https://lh3.googleusercontent.com/a/johndoe");

        assertThat(userProfileService.getByUserId(1L, 1L).avatarUrl())
                .isEqualTo("https://lh3.googleusercontent.com/a/johndoe");
    }

    /**
     * An event is a trust boundary like any other: a scheme that would execute inside an img tag,
     * or a URL too long for the column, leaves the profile on the default avatar instead.
     */
    @Test
    @Transactional
    void createFromRegisteredEvent_unusableAvatar_fallsBackToDefault() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", "javascript:alert(1)");
        userProfileService.createFromRegisteredEvent(2L, "janedoe", "https://x/" + "a".repeat(500));

        assertThat(userProfileService.getByUserId(1L, 1L).avatarUrl()).isNull();
        assertThat(userProfileService.getByUserId(2L, 2L).avatarUrl()).isNull();
    }

    /**
     * The three states the mirrored copy meets, asserted together because the WHERE clause that
     * separates them is one expression: a profile still on the provider URL moves to our copy, one
     * that never had an avatar takes it (that is the backfill for accounts older than the feature),
     * and one whose owner uploaded a picture keeps it.
     */
    @Test
    @Transactional
    void applyMirroredAvatar_replacesTheProviderUrlAndFillsGaps_butNeverTheUsersOwn() {
        String source = "https://lh3.googleusercontent.com/a/x";
        String mirrored = "http://localhost:9000/video-media/avatars/1.jpg";

        userProfileService.createFromRegisteredEvent(1L, "seeded", source);
        userProfileService.createFromRegisteredEvent(2L, "empty", null);
        userProfileService.createFromRegisteredEvent(3L, "chose", null);
        userProfileService.updateOwnProfile(3L, new UpdateProfileRequest(null, null, OWN_AVATAR));

        userProfileService.applyMirroredAvatar(1L, source, mirrored);
        userProfileService.applyMirroredAvatar(2L, source, mirrored);
        userProfileService.applyMirroredAvatar(3L, source, mirrored);

        assertThat(userProfileService.getByUserId(1L, 1L).avatarUrl()).isEqualTo(mirrored);
        assertThat(userProfileService.getByUserId(2L, 2L).avatarUrl()).isEqualTo(mirrored);
        assertThat(userProfileService.getByUserId(3L, 3L).avatarUrl()).isEqualTo(OWN_AVATAR);
    }

    @Test
    @Transactional
    void createFromRegisteredEvent_replay_isNoOp() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

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
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        blockService.block(3L, 1L);

        List<UserProfileResponse> profiles =
                userProfileService.getByUserIds(1L, List.of(2L, 3L, 999L));

        assertThat(profiles).extracting(UserProfileResponse::userId).containsExactly(2L);
    }

    /** A block hides both sides, so which of the two pressed the button must not matter here. */
    @Test
    @Transactional
    void getByUserIds_dropsBlockedIdInEitherDirection() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        blockService.block(1L, 2L);

        assertThat(userProfileService.getByUserIds(1L, List.of(2L))).isEmpty();
        assertThat(userProfileService.getByUserIds(2L, List.of(1L))).isEmpty();
    }

    @Test
    @Transactional
    void getByUserIds_answersInTheOrderAskedAndCollapsesDuplicates() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        userProfileService.createFromRegisteredEvent(3L, "carol", null);

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
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
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
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        blockService.block(1L, 2L);

        assertThat(userProfileService.getByUserId(3L, 1L).displayName()).isEqualTo("alice");
        assertThat(userProfileService.getByUserId(3L, 2L).displayName()).isEqualTo("bob");
    }

    @Test
    @Transactional
    void updateOwnProfile_updatesDisplayNameBioAndAvatar() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("John Doe", "Hello world", "https://example.com/avatar.png"));

        assertThat(updated.displayName()).isEqualTo("John Doe");
        assertThat(updated.bio()).isEqualTo("Hello world");
        assertThat(updated.avatarUrl()).isEqualTo("https://example.com/avatar.png");
    }

    @Test
    @Transactional
    void updateOwnProfile_withOnlyDisplayName_keepsBioAndAvatar() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
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
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        userProfileService.updateOwnProfile(1L, new UpdateProfileRequest(null, "Hello world", null));

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest(null, "", null));

        assertThat(updated.bio()).isNull();
        assertThat(updated.displayName()).isEqualTo("johndoe");
    }

    @Test
    void search_matchesTheHandleAndTheDisplayName() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        userProfileService.createFromRegisteredEvent(2L, "janedoe", null);
        userProfileService.createFromRegisteredEvent(3L, "someoneelse", null);
        userProfileService.updateOwnProfile(3L, new UpdateProfileRequest("John Smith", null, null));

        // "john" is in one handle and in one display name, and both accounts come back.
        assertThat(userProfileService.search(99L, "john", PageRequest.of(0, 20)).getContent())
                .extracting(UserProfileResponse::userId)
                .containsExactlyInAnyOrder(1L, 3L);

        assertThat(userProfileService.search(99L, "doe", PageRequest.of(0, 20)).getContent())
                .extracting(UserProfileResponse::userId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void search_isCaseInsensitive() {
        userProfileService.createFromRegisteredEvent(1L, "JohnDoe", null);

        assertThat(userProfileService.search(99L, "JOHNDOE", PageRequest.of(0, 20)).getContent())
                .extracting(UserProfileResponse::userId)
                .containsExactly(1L);
    }

    @Test
    void search_ordersByFollowerCount() {
        userProfileService.createFromRegisteredEvent(1L, "doe1", null);
        userProfileService.createFromRegisteredEvent(2L, "doe2", null);
        userProfileService.createFromRegisteredEvent(3L, "fan", null);
        followService.follow(3L, 2L);

        assertThat(userProfileService.search(99L, "doe", PageRequest.of(0, 20)).getContent())
                .extracting(UserProfileResponse::userId)
                .containsExactly(2L, 1L);
    }

    @Test
    void search_dropsBlockedProfiles() {
        userProfileService.createFromRegisteredEvent(1L, "doe1", null);
        userProfileService.createFromRegisteredEvent(2L, "doe2", null);
        blockService.block(1L, 2L);

        var page = userProfileService.search(1L, "doe", PageRequest.of(0, 20));

        assertThat(page.getContent())
                .extracting(UserProfileResponse::userId)
                .containsExactly(1L);
    }

    /** "Show me everyone" is not a search, and paging through every profile is what it would cost. */
    @Test
    void search_withABlankQuery_isEmpty() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

        assertThat(userProfileService.search(99L, "   ", PageRequest.of(0, 20))).isEmpty();
        assertThat(userProfileService.search(99L, null, PageRequest.of(0, 20))).isEmpty();
    }
}
