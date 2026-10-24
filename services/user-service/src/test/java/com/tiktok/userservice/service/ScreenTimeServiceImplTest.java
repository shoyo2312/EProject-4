package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.ScreenTimeDayResponse;
import com.tiktok.userservice.dto.response.ScreenTimeWeekResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.entity.UserSettings;
import com.tiktok.userservice.exception.InvalidUserSettingsException;
import com.tiktok.userservice.repository.ScreenTimeDayRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ScreenTimeServiceImplTest {

    private static final Long USER = 1L;
    /** UTC+7 all year, so every instant below is easy to read as local time. */
    private static final String HCM = "Asia/Ho_Chi_Minh";

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ScreenTimeService screenTimeService;

    @Autowired
    private UserSettingsService userSettingsService;

    @Autowired
    private AccountStateService accountStateService;

    // Spied so one test can hand the settings write an entity read before the heartbeat.
    @MockitoSpyBean
    private UserSettingsRepository userSettingsRepository;

    @Autowired
    private ScreenTimeDayRepository screenTimeDayRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        screenTimeDayRepository.deleteAll();
        userSettingsRepository.deleteAll();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void record(int seconds, String instant) {
        screenTimeService.recordHeartbeat(USER, seconds, HCM, Instant.parse(instant));
    }

    private void assertDay(String date, int daySeconds, int nightSeconds) {
        LocalDate day = LocalDate.parse(date);
        ScreenTimeDayResponse stored = screenTimeService.getWeek(USER, day.minusDays(day.getDayOfWeek().getValue() - 1))
                .days().stream()
                .filter(d -> d.date().equals(day))
                .findFirst().orElseThrow();
        assertThat(stored.daySeconds()).as("day seconds on " + date).isEqualTo(daySeconds);
        assertThat(stored.nightSeconds()).as("night seconds on " + date).isEqualTo(nightSeconds);
    }

    @Test
    void nightTime_isTenPmToSixAm() {
        assertThat(ScreenTimeService.isNight(LocalTime.of(5, 59, 59))).isTrue();
        assertThat(ScreenTimeService.isNight(LocalTime.of(6, 0))).isFalse();
        assertThat(ScreenTimeService.isNight(LocalTime.of(21, 59, 59))).isFalse();
        assertThat(ScreenTimeService.isNight(LocalTime.of(22, 0))).isTrue();
    }

    @Test
    void heartbeat_at10LocalCountsAsDayTime() {
        record(60, "2026-10-20T03:00:00Z");

        assertDay("2026-10-20", 60, 0);
    }

    @Test
    void heartbeat_at23LocalCountsAsNightTime() {
        record(60, "2026-10-20T16:00:00Z");

        assertDay("2026-10-20", 0, 60);
    }

    /** 00:30 local on the 21st is still the 20th in UTC; the local date is what the chart shows. */
    @Test
    void heartbeat_afterMidnightLocalLandsOnTheNextDate() {
        record(60, "2026-10-20T17:30:00Z");

        assertDay("2026-10-21", 0, 60);
        assertDay("2026-10-20", 0, 0);
    }

    @Test
    void heartbeat_clampsTo120Seconds() {
        record(500, "2026-10-20T03:00:00Z");

        assertDay("2026-10-20", 120, 0);
    }

    /** Two tabs, or a retried request: every increment lands, none overwrites another. */
    @Test
    void heartbeat_twentyInParallelAddUpExactly() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            done.add(pool.submit(() -> {
                start.await();
                record(60, "2026-10-20T03:00:00Z");
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get();
        }
        pool.shutdown();

        assertDay("2026-10-20", 1200, 0);
    }

    /**
     * No settings row before: the heartbeat creates one, and every other column comes from the
     * table's DEFAULTs — which have to read exactly like UserSettings.defaultsFor().
     */
    @Test
    void heartbeat_storesTheZoneAndCreatesTheSettingsRow() {
        record(60, "2026-10-20T03:00:00Z");

        UserSettingsResponse settings = userSettingsService.getOwnSettings(USER);
        assertThat(settings.timeZone()).isEqualTo(HCM);
        assertThat(settings.commentAudience()).isEqualTo(SettingsAudience.EVERYONE);
        assertThat(settings.weeklyScreenTimeDigest()).isTrue();
        assertThat(settings.filterSpamOffensive()).isTrue();
        assertThat(settings.commentKeywords()).isEmpty();
        assertThat(List.of(settings.pushLikes(), settings.pushComments(), settings.pushNewFollowers(),
                settings.inAppLikes(), settings.inAppComments(), settings.inAppNewFollowers(),
                settings.inAppReposts())).containsOnly(true);
        assertThat(userSettingsService.getSettingsPolicies(List.of(USER)).getFirst().timeZone()).isEqualTo(HCM);

        screenTimeService.recordHeartbeat(USER, 60, "Europe/Paris", Instant.parse("2026-10-20T03:01:00Z"));

        assertThat(userSettingsService.getOwnSettings(USER).timeZone()).isEqualTo("Europe/Paris");
    }

    @Test
    void heartbeat_rejectsAnUnknownZone() {
        assertThatThrownBy(() -> screenTimeService.recordHeartbeat(USER, 60, "Mars/Base", Instant.parse("2026-10-20T03:00:00Z")))
                .isInstanceOf(InvalidUserSettingsException.class)
                .hasMessageContaining("timeZone");

        assertThat(screenTimeDayRepository.count()).isZero();
    }

    @Test
    void week_isSevenDaysMondayFirstZeroFilled() {
        record(60, "2026-10-20T03:00:00Z");

        ScreenTimeWeekResponse week = screenTimeService.getWeek(USER, LocalDate.parse("2026-10-19"));

        assertThat(week.weekStart()).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(week.days()).extracting(ScreenTimeDayResponse::date).containsExactly(
                LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-21"),
                LocalDate.parse("2026-10-22"), LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-24"),
                LocalDate.parse("2026-10-25"));
        assertThat(week.days()).extracting(ScreenTimeDayResponse::daySeconds).containsExactly(0, 60, 0, 0, 0, 0, 0);
        assertThat(week.days()).extracting(ScreenTimeDayResponse::nightSeconds).containsOnly(0);
    }

    @Test
    void week_rejectsANonMondayStart() {
        assertThatThrownBy(() -> screenTimeService.getWeek(USER, LocalDate.parse("2026-10-21")))
                .isInstanceOf(InvalidUserSettingsException.class)
                .hasMessageContaining("weekStart");
    }

    /**
     * A PATCH that read the row before the heartbeat stored a zone saves after it. Every column it
     * maps is written back, so if time_zone were one of them the zone would be gone again.
     */
    @Test
    void zoneWrite_isNotUndoneByAStaleSettingsSave() {
        UpdateUserSettingsRequest first = new UpdateUserSettingsRequest();
        first.setCommentAudience("FRIENDS");
        userSettingsService.updateOwnSettings(USER, first);
        UserSettings readBeforeTheHeartbeat = userSettingsRepository.findByUserIdAndDeletedAtIsNull(USER).orElseThrow();

        record(60, "2026-10-20T03:00:00Z");

        doReturn(Optional.of(readBeforeTheHeartbeat)).when(userSettingsRepository).findByUserIdAndDeletedAtIsNull(USER);
        UpdateUserSettingsRequest stale = new UpdateUserSettingsRequest();
        stale.setPushLikes(false);
        userSettingsService.updateOwnSettings(USER, stale);

        assertThat(jdbcTemplate.queryForObject(
                "select time_zone from user_settings where user_id = ? and deleted_at is null", String.class, USER))
                .isEqualTo(HCM);
    }

    @Test
    void erasingTheAccount_takesItsScreenTimeWithIt() {
        record(60, "2026-10-20T03:00:00Z");

        accountStateService.erase(USER, Instant.now());

        assertThat(screenTimeService.getWeek(USER, LocalDate.parse("2026-10-19")).days())
                .extracting(ScreenTimeDayResponse::daySeconds).containsOnly(0);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from screen_time_days where user_id = ? and deleted_at is not null", Long.class, USER))
                .isEqualTo(1L);
    }
}
