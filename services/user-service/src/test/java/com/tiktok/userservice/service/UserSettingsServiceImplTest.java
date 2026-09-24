package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
import com.tiktok.userservice.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UserSettingsServiceImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private UserSettingsService userSettingsService;

    // Spied rather than autowired so one test can force what a lost first-write race saw.
    @SpyBean
    private UserSettingsRepository userSettingsRepository;

    @BeforeEach
    void setUp() {
        userSettingsRepository.deleteAll();
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
}
