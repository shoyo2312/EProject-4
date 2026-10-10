package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.FollowRequestRepository;
import com.tiktok.userservice.repository.OutboxEventRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UserSettingsServiceImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private UserSettingsService userSettingsService;

    // Spied rather than autowired so one test can force what a lost first-write race saw.
    @MockitoSpyBean
    private UserSettingsRepository userSettingsRepository;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private FollowService followService;

    @Autowired
    private FollowRequestRepository followRequestRepository;

    @Autowired
    private UserFollowRepository userFollowRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void setUp() {
        userSettingsRepository.deleteAll();
        outboxEventRepository.deleteAll();
        followRequestRepository.deleteAll();
        userFollowRepository.deleteAll();
        userProfileRepository.deleteAll();
        // The outbox is drained on a schedule in tests too, and the dispatcher waits on the future
        // the template hands back.
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    /**
     * No row at registration, so the read has to answer for accounts that have none — and must not
     * create one, or every profile view of one's own settings would write.
     */
    @Test
    void read_withNoRow_answersTheDefaultsAndWritesNothing() {
        UserSettingsResponse settings = userSettingsService.getOwnSettings(1L);

        assertThat(settings.commentAudience()).isEqualTo(SettingsAudience.EVERYONE);
        assertThat(settings.dmAudience()).isEqualTo(SettingsAudience.EVERYONE);
        assertThat(settings.screenTimeLimitMinutes()).isNull();
        assertThat(settings.sleepHoursStart()).isNull();
        assertThat(settings.sleepHoursEnd()).isNull();
        assertThat(settings.weeklyScreenTimeDigest()).isTrue();

        assertThat(userSettingsRepository.findByUserIdAndDeletedAtIsNull(1L)).isEmpty();
    }

    @Test
    void write_withNoRow_insertsThePatchOverTheDefaults() {
        UpdateUserSettingsRequest request = new UpdateUserSettingsRequest();
        request.setCommentAudience("FRIENDS");

        UserSettingsResponse settings = userSettingsService.updateOwnSettings(1L, request);

        assertThat(settings.commentAudience()).isEqualTo(SettingsAudience.FRIENDS);
        // Untouched by the patch, so still the default rather than null.
        assertThat(settings.dmAudience()).isEqualTo(SettingsAudience.EVERYONE);
        assertThat(settings.weeklyScreenTimeDigest()).isTrue();
        assertThat(userSettingsRepository.findByUserIdAndDeletedAtIsNull(1L)).isPresent();
    }

    /** The whole point of the PATCH contract: absent and explicitly null mean different things. */
    @Test
    void write_absentKeyLeavesTheField_explicitNullClearsANullableOne() {
        UpdateUserSettingsRequest first = new UpdateUserSettingsRequest();
        first.setScreenTimeLimitMinutes(60);
        first.setDmAudience("NO_ONE");
        userSettingsService.updateOwnSettings(1L, first);

        UpdateUserSettingsRequest second = new UpdateUserSettingsRequest();
        second.setCommentAudience("FRIENDS");
        UserSettingsResponse afterAbsent = userSettingsService.updateOwnSettings(1L, second);

        assertThat(afterAbsent.screenTimeLimitMinutes()).isEqualTo(60);
        assertThat(afterAbsent.dmAudience()).isEqualTo(SettingsAudience.NO_ONE);

        UpdateUserSettingsRequest third = new UpdateUserSettingsRequest();
        third.setScreenTimeLimitMinutes(null);
        UserSettingsResponse afterNull = userSettingsService.updateOwnSettings(1L, third);

        assertThat(afterNull.screenTimeLimitMinutes()).isNull();
        assertThat(afterNull.dmAudience()).isEqualTo(SettingsAudience.NO_ONE);
    }

    /**
     * NOT NULL in the database, so there is no value to clear it to. Refused rather than treated
     * as an absent key: a client that sent it meant something by it.
     */
    @Test
    void write_explicitNullOnANonNullableField_isRejected() {
        UpdateUserSettingsRequest request = new UpdateUserSettingsRequest();
        request.setCommentAudience(null);

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, request))
                .isInstanceOf(InvalidUserSettingsException.class)
                .hasMessageContaining("commentAudience");
    }

    @Test
    void sleepHours_mustBeSetOrClearedTogether() {
        UpdateUserSettingsRequest onlyStart = new UpdateUserSettingsRequest();
        onlyStart.setSleepHoursStart(LocalTime.of(22, 30));

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, onlyStart))
                .isInstanceOf(InvalidUserSettingsException.class);

        UpdateUserSettingsRequest both = new UpdateUserSettingsRequest();
        both.setSleepHoursStart(LocalTime.of(22, 30));
        both.setSleepHoursEnd(LocalTime.of(7, 0));
        userSettingsService.updateOwnSettings(1L, both);

        // Clearing one end of a stored window leaves the other set, which is the same invalid
        // state — the rule is checked against the merged result, not against the patch.
        UpdateUserSettingsRequest clearOne = new UpdateUserSettingsRequest();
        clearOne.setSleepHoursEnd(null);

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, clearOne))
                .isInstanceOf(InvalidUserSettingsException.class);
    }

    /** A window that crosses midnight is the normal case, so only start == end is refused. */
    @Test
    void sleepHours_acceptAWindowAcrossMidnightButNotAnEmptyOne() {
        UpdateUserSettingsRequest crossesMidnight = new UpdateUserSettingsRequest();
        crossesMidnight.setSleepHoursStart(LocalTime.of(23, 0));
        crossesMidnight.setSleepHoursEnd(LocalTime.of(6, 30));

        UserSettingsResponse stored = userSettingsService.updateOwnSettings(1L, crossesMidnight);
        assertThat(stored.sleepHoursStart()).isEqualTo(LocalTime.of(23, 0));
        assertThat(stored.sleepHoursEnd()).isEqualTo(LocalTime.of(6, 30));

        UpdateUserSettingsRequest sameMinute = new UpdateUserSettingsRequest();
        sameMinute.setSleepHoursStart(LocalTime.of(22, 0));
        sameMinute.setSleepHoursEnd(LocalTime.of(22, 0));

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, sameMinute))
                .isInstanceOf(InvalidUserSettingsException.class);
    }

    /**
     * Absence would be indistinguishable from "no restriction", and the first caller to read it
     * that way has silently switched the feature off.
     */
    @Test
    void internalPolicies_answerEveryIdAskedFor_withDefaultsForTheOnesWithNoRow() {
        UpdateUserSettingsRequest request = new UpdateUserSettingsRequest();
        request.setCommentAudience("NO_ONE");
        userSettingsService.updateOwnSettings(1L, request);

        List<UserSettingsPolicyResponse> policies = userSettingsService.getSettingsPolicies(List.of(1L, 999L));

        assertThat(policies).hasSize(2);
        assertThat(policies.get(0).userId()).isEqualTo(1L);
        assertThat(policies.get(0).commentAudience()).isEqualTo(SettingsAudience.NO_ONE);
        assertThat(policies.get(1).userId()).isEqualTo(999L);
        assertThat(policies.get(1).commentAudience()).isEqualTo(SettingsAudience.EVERYONE);
        assertThat(policies.get(1).dmAudience()).isEqualTo(SettingsAudience.EVERYONE);
    }

    @Test
    void internalPolicies_overTheBatchCap_isRejected() {
        List<Long> ids = LongStream.rangeClosed(1, UserProfileService.MAX_BATCH_IDS + 1).boxed().toList();

        assertThatThrownBy(() -> userSettingsService.getSettingsPolicies(ids))
                .isInstanceOf(TooManyProfileIdsException.class);
    }

    /**
     * Two devices changing a setting for the first time both read no row and both insert; only
     * uq_user_settings_user_id stops the second. Forced the same way
     * {@link DuplicateRelationshipRaceTest} forces it — the row is committed and the pre-read is
     * stubbed to report what the losing request saw a moment earlier — because racing real threads
     * decides nothing reliably.
     *
     * <p>It has to be the same 409 a concurrent write to an existing row produces: the caller's
     * move is identical either way, and a 500 tells them to give up on a request that will
     * succeed on retry.
     */
    @Test
    void write_losingAFirstWriteRace_isTheRetryable409NotAServerError() {
        UpdateUserSettingsRequest first = new UpdateUserSettingsRequest();
        first.setCommentAudience("FRIENDS");
        userSettingsService.updateOwnSettings(1L, first);

        doReturn(Optional.empty()).when(userSettingsRepository).findByUserIdAndDeletedAtIsNull(1L);

        UpdateUserSettingsRequest second = new UpdateUserSettingsRequest();
        second.setDmAudience("NO_ONE");

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, second))
                .isInstanceOf(OptimisticLockingFailureException.class);
    }

    private static UpdateUserSettingsRequest privacy(Boolean privateAccount) {
        UpdateUserSettingsRequest request = new UpdateUserSettingsRequest();
        request.setPrivateAccount(privateAccount);
        return request;
    }

    private List<OutboxEvent> privacyEvents() {
        return outboxEventRepository.findAll().stream()
                .filter(event -> "UserPrivacyChangedEvent".equals(event.getEventType()))
                .toList();
    }

    /** Off the profile, where every viewer reads it — and false before the profile exists. */
    @Test
    void read_answersPrivateAccountOffTheProfile_andFalseWithoutOne() {
        assertThat(userSettingsService.getOwnSettings(1L).privateAccount()).isFalse();

        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userSettingsService.updateOwnSettings(1L, privacy(true));

        assertThat(userSettingsService.getOwnSettings(1L).privateAccount()).isTrue();
    }

    /** A value equal to the stored one queues nothing, whatever else the same PATCH changes. */
    @Test
    void privacy_queuesOneEventPerChange_andNoneWhenUnchanged() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);

        assertThat(userSettingsService.updateOwnSettings(1L, privacy(true)).privateAccount()).isTrue();
        userSettingsService.updateOwnSettings(1L, privacy(true));
        UpdateUserSettingsRequest unrelated = new UpdateUserSettingsRequest();
        unrelated.setCommentAudience("FRIENDS");
        assertThat(userSettingsService.updateOwnSettings(1L, unrelated).privateAccount()).isTrue();

        assertThat(privacyEvents())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getAggregateId()).isEqualTo("1");
                    assertThat(event.getPayload()).contains("\"privateAccount\":true");
                });
    }

    @Test
    void privacy_explicitNull_isRejected() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, privacy(null)))
                .isInstanceOf(InvalidUserSettingsException.class)
                .hasMessageContaining("privateAccount");
    }

    /** No profile, nothing to put the flag on — and the settings row the same PATCH wrote goes too. */
    @Test
    void privacy_withNoProfile_isNotFound() {
        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, privacy(true)))
                .isInstanceOf(UserProfileNotFoundException.class);

        assertThat(userSettingsRepository.findByUserIdAndDeletedAtIsNull(1L)).isEmpty();
    }

    /** Nothing gates following any more, and these people already asked. */
    @Test
    void goingPublic_approvesEveryPendingRequest() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        userProfileService.createFromRegisteredEvent(3L, "carol", null);
        userSettingsService.updateOwnSettings(1L, privacy(true));
        followService.follow(2L, 1L);
        followService.follow(3L, 1L);

        userSettingsService.updateOwnSettings(1L, privacy(false));

        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(2L, 1L)).isPresent();
        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(3L, 1L)).isPresent();
        assertThat(followRequestRepository.findByTargetIdAndDeletedAtIsNull(1L)).isEmpty();
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow().getFollowerCount())
                .isEqualTo(2);
    }

    /** "Your existing followers won't be affected." */
    @Test
    void goingPrivate_leavesExistingFollowersAlone() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        userProfileService.createFromRegisteredEvent(2L, "bob", null);
        followService.follow(2L, 1L);

        userSettingsService.updateOwnSettings(1L, privacy(true));

        assertThat(userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(2L, 1L)).isPresent();
        assertThat(userProfileRepository.findByUserIdAndDeletedAtIsNull(1L).orElseThrow().getFollowerCount())
                .isEqualTo(1);
    }

    /** One transaction: a PATCH refused for any key leaves the switch, and its announcement, where they were. */
    @Test
    void privacy_inAPatchThatFailsValidation_changesNothing() {
        userProfileService.createFromRegisteredEvent(1L, "alice", null);
        UpdateUserSettingsRequest request = privacy(true);
        request.setSleepHoursStart(LocalTime.of(22, 0));

        assertThatThrownBy(() -> userSettingsService.updateOwnSettings(1L, request))
                .isInstanceOf(InvalidUserSettingsException.class);

        assertThat(userSettingsService.getOwnSettings(1L).privateAccount()).isFalse();
        assertThat(privacyEvents()).isEmpty();
    }
}
