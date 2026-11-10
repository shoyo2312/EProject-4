package com.tiktok.userservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.entity.UserSettings;
import com.tiktok.userservice.repository.OutboxEventRepository;
import com.tiktok.userservice.repository.ScreenTimeDayRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
// The schedule is off: each test runs the job itself, at an instant it chooses.
@TestPropertySource(properties = "user.screen-time.digest-cron=-")
class WeeklyScreenTimeDigestTest {

    private static final Long USER = 1L;
    private static final Long OTHER = 2L;
    /** UTC+7 all year. */
    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Monday 2026-10-26, 09:00 in HCM: the week of Monday 2026-10-19 is due. */
    private static final Instant DUE = Instant.parse("2026-10-26T02:00:00Z");

    @Container
    @ServiceConnection
    static PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private WeeklyScreenTimeDigest digest;

    @Autowired
    private ScreenTimeService screenTimeService;

    @Autowired
    private UserSettingsService userSettingsService;

    // Spied so one test can hand a settings save the row as it was before the job claimed a week.
    @MockitoSpyBean
    private UserSettingsRepository userSettingsRepository;

    @Autowired
    private ScreenTimeDayRepository screenTimeDayRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        screenTimeDayRepository.deleteAll();
        userSettingsRepository.deleteAll();
        outboxEventRepository.deleteAll();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    }

    private void spend(Long userId, int seconds, String instant) {
        screenTimeService.recordHeartbeat(userId, seconds, HCM.getId(), Instant.parse(instant));
    }

    private List<OutboxEvent> summaries() {
        return outboxEventRepository.findAll().stream()
                .filter(event -> "WeeklyScreenTimeSummaryEvent".equals(event.getEventType()))
                .toList();
    }

    @Test
    void dueWeek_mondayBeforeNineIsNotDue() {
        assertThat(WeeklyScreenTimeDigest.dueWeek(Instant.parse("2026-10-26T01:59:00Z"), HCM)).isNull();
    }

    @Test
    void dueWeek_mondayAtNineIsLastWeek() {
        assertThat(WeeklyScreenTimeDigest.dueWeek(Instant.parse("2026-10-26T02:00:00Z"), HCM))
                .isEqualTo(LocalDate.parse("2026-10-19"));
    }

    /** Sunday 23:00 local: the week still running is not due, the one before it is. */
    @Test
    void dueWeek_sundayNightIsTheWeekBefore() {
        assertThat(WeeklyScreenTimeDigest.dueWeek(Instant.parse("2026-10-25T16:00:00Z"), HCM))
                .isEqualTo(LocalDate.parse("2026-10-12"));
    }

    @Test
    void runOnce_writesOneOutboxRowWithTheWeeksTotal() {
        spend(USER, 120, "2026-10-20T03:00:00Z");   // Tuesday 10:00
        spend(USER, 60, "2026-10-25T16:30:00Z");    // Sunday 23:30, still that week
        spend(USER, 60, "2026-10-26T01:00:00Z");    // Monday 08:00, the next week

        assertThat(digest.runOnce(DUE)).isEqualTo(1);

        assertThat(summaries()).singleElement().satisfies(row -> {
            assertThat(row.getAggregateId()).isEqualTo("1");
            assertThat(row.getPayload()).contains("\"userId\":1", "\"weekStart\":\"2026-10-19\"", "\"totalSeconds\":180");
        });
    }

    @Test
    void runOnce_secondRunWritesNothing() {
        spend(USER, 60, "2026-10-20T03:00:00Z");

        assertThat(digest.runOnce(DUE)).isEqualTo(1);
        assertThat(digest.runOnce(DUE.plus(Duration.ofHours(1)))).isZero();

        assertThat(summaries()).hasSize(1);
    }

    /** Nothing worth an inbox entry, and the week is still claimed: time added to it later is not sent either. */
    @Test
    void runOnce_zeroWeekIsClaimedButNotSent() {
        spend(USER, 60, "2026-10-13T03:00:00Z");    // the week before, so the account has a zone

        assertThat(digest.runOnce(DUE)).isZero();
        spend(USER, 60, "2026-10-20T03:00:00Z");
        assertThat(digest.runOnce(DUE.plus(Duration.ofHours(1)))).isZero();

        assertThat(summaries()).isEmpty();
    }

    @Test
    void runOnce_skipsSwitchOffAndUnknownZone() {
        spend(USER, 60, "2026-10-20T03:00:00Z");
        UpdateUserSettingsRequest off = new UpdateUserSettingsRequest();
        off.setWeeklyScreenTimeDigest(false);
        userSettingsService.updateOwnSettings(USER, off);
        // Time recorded but no zone on the account, so its Monday 09:00 is unknown.
        UpdateUserSettingsRequest anything = new UpdateUserSettingsRequest();
        anything.setCommentAudience("FRIENDS");
        userSettingsService.updateOwnSettings(OTHER, anything);
        transactionTemplate.executeWithoutResult(status -> screenTimeDayRepository.addSeconds(
                SnowflakeIdGenerator.nextId(), OTHER, LocalDate.parse("2026-10-20"), 60, 0));

        assertThat(digest.runOnce(DUE)).isZero();

        assertThat(summaries()).isEmpty();
    }

    /** Two instances of the service run the job at the same minute: one entry, not two. */
    @Test
    void runOnce_twoConcurrentRunsWriteOneRow() throws Exception {
        spend(USER, 60, "2026-10-20T03:00:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> first = pool.submit(() -> {
            start.await();
            return digest.runOnce(DUE);
        });
        Future<Integer> second = pool.submit(() -> {
            start.await();
            return digest.runOnce(DUE);
        });
        start.countDown();

        assertThat(first.get() + second.get()).isEqualTo(1);
        pool.shutdown();
        assertThat(summaries()).hasSize(1);
    }

    /**
     * A settings save that read the row before the job claimed the week saves after it. If it
     * wrote the claim column back, the next run would send the same week again.
     */
    @Test
    void runOnce_aSettingsSaveAfterTheClaimDoesNotReopenTheWeek() {
        spend(USER, 60, "2026-10-20T03:00:00Z");
        UserSettings readBeforeTheClaim = userSettingsRepository.findByUserIdAndDeletedAtIsNull(USER).orElseThrow();
        assertThat(digest.runOnce(DUE)).isEqualTo(1);

        doReturn(Optional.of(readBeforeTheClaim)).when(userSettingsRepository).findByUserIdAndDeletedAtIsNull(USER);
        UpdateUserSettingsRequest stale = new UpdateUserSettingsRequest();
        stale.setWeeklyScreenTimeDigest(true);
        userSettingsService.updateOwnSettings(USER, stale);

        assertThat(digest.runOnce(DUE.plus(Duration.ofHours(1)))).isZero();
        assertThat(summaries()).hasSize(1);
    }
}
