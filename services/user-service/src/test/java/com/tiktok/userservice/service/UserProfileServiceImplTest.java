package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.dto.response.UserStatsCountsResponse;
import com.tiktok.userservice.exception.DisplayNameChangeTooSoonException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.LongStream;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
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
        // The follow-changed realtime publish (confirmed send) needs a non-null future to
        // .get() on, same as every other confirmed-send test in this codebase.
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
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

    /**
     * The handle lookup a `/@name` URL needs. Case-insensitive because the handle is typed by
     * hand, and a block hides the account here exactly as it does for the id lookup — otherwise
     * the handle would be a way around the guard.
     */
    @Test
    @Transactional
    void getByUsername_findsTheProfileIgnoringCase_andHidesBlockedAccounts() {
        userProfileService.createFromRegisteredEvent(1L, "JohnDoe", null);
        userProfileService.createFromRegisteredEvent(2L, "janedoe", null);

        assertThat(userProfileService.getByUsername(2L, "johndoe").userId()).isEqualTo(1L);

        blockService.block(1L, 2L);

        assertThatThrownBy(() -> userProfileService.getByUsername(2L, "johndoe"))
                .isInstanceOf(UserProfileNotFoundException.class);
        assertThatThrownBy(() -> userProfileService.getByUsername(2L, "nobody"))
                .isInstanceOf(UserProfileNotFoundException.class);
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
        String mirrored = "http://localhost:9000/video-media/avatars/1";

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

    /**
     * A new account's copy can land before the registration creating its profile. Dropped, that
     * profile came up on the provider's URL and stayed there; thrown, the redelivery after the
     * registration puts the copy on it.
     */
    @Test
    void applyMirroredAvatar_beforeTheProfileExists_throwsSoTheRetryCanApplyIt() {
        String source = "https://lh3.googleusercontent.com/a/x";
        String mirrored = "http://localhost:9000/video-media/avatars/1";

        assertThatThrownBy(() -> userProfileService.applyMirroredAvatar(1L, source, mirrored))
                .isInstanceOf(UserProfileNotFoundException.class);

        userProfileService.createFromRegisteredEvent(1L, "johndoe", source);
        userProfileService.applyMirroredAvatar(1L, source, mirrored);

        assertThat(userProfileService.getByUserId(1L, 1L).avatarUrl()).isEqualTo(mirrored);
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

    /**
     * The counts batch is viewer-less on purpose — a follower count is not PII — so a block must
     * not hide a row here the way it does in getByUserIds. Unknown ids are still simply absent.
     */
    @Test
    @Transactional
    void getStatsByUserIds_ignoresBlocksAndDropsUnknownIds() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        blockService.block(1L, 2L);

        List<UserStatsCountsResponse> counts =
                userProfileService.getStatsByUserIds(List.of(1L, 2L, 2L, 999L));

        assertThat(counts).extracting(UserStatsCountsResponse::userId).containsExactlyInAnyOrder(1L, 2L);
    }

    /** Over the cap is a rejection, not a silently shortened list — see getStatsByUserIds. */
    @Test
    @Transactional
    void getStatsByUserIds_aboveTheCap_isRejected() {
        List<Long> tooMany = LongStream.rangeClosed(1, UserProfileService.MAX_BATCH_IDS + 1)
                .boxed()
                .toList();

        assertThatThrownBy(() -> userProfileService.getStatsByUserIds(tooMany))
                .isInstanceOf(TooManyProfileIdsException.class);
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

    /** A signed-out reader is a null viewer: a block between two others hides nothing from them. */
    @Test
    @Transactional
    void aGuest_seesProfilesByIdHandleAndBatch() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        blockService.block(1L, 2L);

        assertThat(userProfileService.getByUserId(null, 1L).displayName()).isEqualTo("alice");
        assertThat(userProfileService.getByUsername(null, "bob").userId()).isEqualTo(2L);
        assertThat(userProfileService.getByUserIds(null, List.of(1L, 2L)))
                .extracting(UserProfileResponse::userId).containsExactly(1L, 2L);
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
                1L, new UpdateProfileRequest(null, "Hello world", "https://example.com/avatar.png"));

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

    /** Unescaped, "_" matched any one character and so listed every profile on the platform. */
    @Test
    void search_treatsWildcardsAsLiteralText() {
        userProfileService.createFromRegisteredEvent(1L, "john_doe", null);
        userProfileService.createFromRegisteredEvent(2L, "johndoe", null);

        assertThat(userProfileService.search(99L, "_", PageRequest.of(0, 20)).getContent())
                .extracting(UserProfileResponse::userId)
                .containsExactly(1L);
        assertThat(userProfileService.search(99L, "%", PageRequest.of(0, 20))).isEmpty();
    }

    /** "Show me everyone" is not a search, and paging through every profile is what it would cost. */
    @Test
    void search_withABlankQuery_isEmpty() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

        assertThat(userProfileService.search(99L, "   ", PageRequest.of(0, 20))).isEmpty();
        assertThat(userProfileService.search(99L, null, PageRequest.of(0, 20))).isEmpty();
    }

    /** What search-service asks: a fragment of a handle, most-followed first, capped. */
    @Test
    void searchIds_findsAPartialHandleMostFollowedFirst() {
        userProfileService.createFromRegisteredEvent(1L, "vmh26122004", null);
        userProfileService.createFromRegisteredEvent(2L, "xvmh", null);
        userProfileService.createFromRegisteredEvent(3L, "fan", null);
        followService.follow(3L, 2L);

        assertThat(userProfileService.searchIds("VMH", 50)).containsExactly(2L, 1L);
        assertThat(userProfileService.searchIds("vmh", 1)).containsExactly(2L);
        assertThat(userProfileService.searchIds(" ", 50)).isEmpty();
    }

    /** The trigram indexes cannot serve these, so each one was a scan of every profile. */
    @Test
    void searchIds_refusesTermsTheIndexesCannotServe() {
        userProfileService.createFromRegisteredEvent(1L, "vmh26122004", null);

        assertThat(userProfileService.searchIds("vm", 50)).isEmpty();
        assertThat(userProfileService.searchIds(" v ", 50)).isEmpty();
        assertThat(userProfileService.searchIds("v".repeat(101), 50)).isEmpty();
        assertThat(userProfileService.searchIds("vmh", 50)).containsExactly(1L);
    }

    @Test
    @Transactional
    void privateAccount_startsFalse_andFollowsTheColumn() {
        userProfileService.createFromRegisteredEvent(70L, "vera", null);
        assertThat(userProfileService.getByUserId(70L, 70L).privateAccount()).isFalse();
        long versionBefore = userProfileRepository.findByUserIdAndDeletedAtIsNull(70L).orElseThrow().getVersion();

        assertThat(userProfileRepository.updatePrivateAccount(70L, true)).isEqualTo(1);

        assertThat(userProfileService.getByUserId(70L, 70L).privateAccount()).isTrue();
        // The version moves with the flag, so an edit of the same profile that read it before the
        // switch fails on its version instead of answering with the old value.
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(70L).orElseThrow().getVersion())
                .isGreaterThan(versionBefore);
        // Conditional on the stored value: a repeat changes nothing and says so, which is what
        // keeps two racing PATCHes down to one announcement.
        assertThat(userProfileRepository.updatePrivateAccount(70L, true)).isZero();
        assertThat(userProfileRepository.updatePrivateAccount(999L, true)).isZero();
    }

    @Test
    @Transactional
    void updateOwnProfile_aNewName_saysWhenTheNextMayFollow() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        assertThat(userProfileService.getByUserId(1L, 1L).displayNameChangeableAt()).isNull();

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("John Doe", null, null));

        assertThat(updated.displayNameChangeableAt())
                .isCloseTo(Instant.now().plus(DisplayNameCooldown.PERIOD), within(1, ChronoUnit.MINUTES));
    }

    /** The whole request fails, bio included: half-applying it would hide that the name was refused. */
    @Test
    @Transactional
    void updateOwnProfile_aSecondNameWithinSevenDays_isRefused() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        userProfileService.updateOwnProfile(1L, new UpdateProfileRequest("John Doe", null, null));

        assertThatThrownBy(() -> userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("Johnny", "Hello world", null)))
                .isInstanceOf(DisplayNameChangeTooSoonException.class)
                .hasFieldOrPropertyWithValue("code", "DISPLAY_NAME_CHANGE_TOO_SOON")
                .hasFieldOrPropertyWithValue("status", HttpStatus.TOO_MANY_REQUESTS);

        UserProfileResponse current = userProfileService.getByUserId(1L, 1L);
        assertThat(current.displayName()).isEqualTo("John Doe");
        assertThat(current.bio()).isNull();
    }

    @Test
    @Transactional
    void updateOwnProfile_theCurrentNameSentBack_isNeitherRefusedNorCounted() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        Instant changeableAt = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("John Doe", null, null)).displayNameChangeableAt();

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("John Doe", "Hello world", null));

        assertThat(updated.bio()).isEqualTo("Hello world");
        assertThat(updated.displayNameChangeableAt()).isEqualTo(changeableAt);
    }

    @Test
    @Transactional
    void updateOwnProfile_theBio_isNotLimited() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        userProfileService.updateOwnProfile(1L, new UpdateProfileRequest("John Doe", "one", null));

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest(null, "two", null));

        assertThat(updated.bio()).isEqualTo("two");
    }

    @Test
    @Transactional
    void updateOwnProfile_sevenDaysAfterTheLastName_anotherIsAllowed() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow()
                .changeDisplayName("John Doe", Instant.now().minus(DisplayNameCooldown.PERIOD));

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("Johnny", null, null));

        assertThat(updated.displayName()).isEqualTo("Johnny");
    }

    /**
     * The web form once sent names untrimmed, so "John Doe " can be stored. The same form now sends
     * it back trimmed, and that must not read as a second name inside the week.
     */
    @Test
    @Transactional
    void updateOwnProfile_aStoredNameSentBackTrimmed_isNeitherRefusedNorCounted() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        Instant changedAt = Instant.now().minus(1, ChronoUnit.DAYS);
        userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow()
                .changeDisplayName("John Doe ", changedAt);

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("John Doe", "Hello world", null));

        assertThat(updated.bio()).isEqualTo("Hello world");
        assertThat(updated.displayNameChangeableAt()).isEqualTo(changedAt.plus(DisplayNameCooldown.PERIOD));
    }

    @Test
    @Transactional
    void updateOwnProfile_aNewName_isStoredStripped() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

        UserProfileResponse updated = userProfileService.updateOwnProfile(
                1L, new UpdateProfileRequest("  John Doe  ", null, null));

        assertThat(updated.displayName()).isEqualTo("John Doe");
    }

    @Test
    @Transactional
    void whenTheNameMayNextChange_isToldToTheOwnerAlone() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);
        userProfileService.createFromRegisteredEvent(2L, "janedoe", null);
        userProfileService.updateOwnProfile(1L, new UpdateProfileRequest("John Doe", null, null));

        assertThat(userProfileService.getByUserId(1L, 1L).displayNameChangeableAt()).isNotNull();
        assertThat(userProfileService.getByUsername(1L, "johndoe").displayNameChangeableAt()).isNotNull();

        assertThat(userProfileService.getByUserId(2L, 1L).displayNameChangeableAt()).isNull();
        assertThat(userProfileService.getByUsername(2L, "johndoe").displayNameChangeableAt()).isNull();
        assertThat(userProfileService.getByUserIds(2L, List.of(1L)))
                .singleElement()
                .extracting(UserProfileResponse::displayNameChangeableAt)
                .isNull();
    }

    @Test
    @Transactional
    void applyUsernameChange_movesTheHandle_andLeavesTheNameAlone() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

        userProfileService.applyUsernameChange(1L, "john.new");

        UserProfileResponse renamed = userProfileService.getByUsername(2L, "john.new");
        assertThat(renamed.userId()).isEqualTo(1L);
        assertThat(renamed.displayName()).isEqualTo("johndoe");
        assertThatThrownBy(() -> userProfileService.getByUsername(2L, "johndoe"))
                .isInstanceOf(UserProfileNotFoundException.class);
    }

    /**
     * A handle given up in auth-service can be taken before this service hears of the rename that
     * gave it up. The profile still showing it is the stale one, so it loses the handle rather
     * than share it — whichever of the two events arrives first.
     */
    @Test
    @Transactional
    void applyUsernameChange_takesTheHandleOffAProfileStillShowingIt() {
        userProfileService.createFromRegisteredEvent(1L, "JohnDoe", null);
        userProfileService.createFromRegisteredEvent(2L, "janedoe", null);

        userProfileService.applyUsernameChange(2L, "johndoe");

        assertThat(userProfileService.getByUsername(3L, "johndoe").userId()).isEqualTo(2L);
        assertThat(userProfileService.getByUserId(3L, 1L).username()).isNull();
    }

    @Test
    @Transactional
    void createFromRegisteredEvent_takesTheHandleOffAProfileStillShowingIt() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

        // 1 has renamed away from "johndoe" and 2 signed up with it, but the registration got here
        // before the rename did.
        userProfileService.createFromRegisteredEvent(2L, "johndoe", null);
        assertThat(userProfileService.getByUsername(3L, "johndoe").userId()).isEqualTo(2L);

        userProfileService.applyUsernameChange(1L, "john.new");
        assertThat(userProfileService.getByUsername(3L, "john.new").userId()).isEqualTo(1L);
        assertThat(userProfileService.getByUsername(3L, "johndoe").userId()).isEqualTo(2L);
    }

    @Test
    void applyUsernameChange_beforeTheProfileExists_throwsSoTheEventIsRetried() {
        userProfileService.createFromRegisteredEvent(1L, "johndoe", null);

        assertThatThrownBy(() -> userProfileService.applyUsernameChange(404L, "johndoe"))
                .isInstanceOf(UserProfileNotFoundException.class);

        // The release is rolled back with the rest, so nothing has moved before the retry.
        assertThat(userProfileService.getByUsername(2L, "johndoe").userId()).isEqualTo(1L);
    }
}
