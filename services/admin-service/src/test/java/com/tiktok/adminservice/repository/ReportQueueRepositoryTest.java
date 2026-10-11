package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue ordering and the auto-dismiss sweep are raw SQL against Postgres — a CASE of weights,
 * a grouped subquery and a batched UPDATE. None of it can be checked by mocking a repository, and
 * getting it wrong is silent: the queue still returns rows, the sweep still closes reports, just
 * the wrong ones. Runs against the real schema, Flyway included, because
 * {@code report_reason_weights} and its seed rows are half the behaviour.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ReportQueueRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private TestEntityManager entityManager;

    /** Each report needs its own reporter — the one-per-reporter-per-target index says so. */
    private long nextReporterId = 1;

    private static final String SPAM = "Deceptive behavior and spam";
    private static final String SELF_HARM = "Suicide and self-harm";

    @Test
    void theQueueRanksOneSeriousReportAboveAFewSpamOnes() {
        // 3 × weight 2 = 6 against 1 × weight 10 = 10.
        report("v-spam", SPAM, hoursAgo(5));
        report("v-spam", SPAM, hoursAgo(4));
        report("v-spam", SPAM, hoursAgo(3));
        report("v-serious", SELF_HARM, hoursAgo(1));

        List<ReportQueueRow> queue = reportRepository.findPendingQueue(PageRequest.of(0, 10)).getContent();

        assertThat(queue).extracting(ReportQueueRow::getTargetId)
                .containsExactly("v-serious", "v-spam");
        assertThat(queue.get(0).getPriority()).isEqualTo(10);
        assertThat(queue.get(1).getPriority()).isEqualTo(6);
        assertThat(queue.get(1).getReportCount()).isEqualTo(3);
    }

    @Test
    void aCrowdOutweighsASingleSeriousReport() {
        // 6 × weight 2 = 12 against 1 × weight 10 = 10: neither half carries a row alone.
        for (int i = 0; i < 6; i++) {
            report("v-brigaded", SPAM, hoursAgo(5));
        }
        report("v-serious", SELF_HARM, hoursAgo(1));

        assertThat(reportRepository.findPendingQueue(PageRequest.of(0, 10)).getContent())
                .extracting(ReportQueueRow::getTargetId)
                .containsExactly("v-brigaded", "v-serious");
    }

    @Test
    void equalPriorityGoesToWhoeverWaitedLongest() {
        report("v-new", SPAM, hoursAgo(1));
        report("v-old", SPAM, hoursAgo(50));

        assertThat(reportRepository.findPendingQueue(PageRequest.of(0, 10)).getContent())
                .extracting(ReportQueueRow::getTargetId)
                .containsExactly("v-old", "v-new");
    }

    @Test
    void theQueueShowsTheNewestReasonAndTheHeaviestSeverity() {
        report("v1", SELF_HARM, hoursAgo(9));
        report("v1", SPAM, hoursAgo(1));

        ReportQueueRow row = reportRepository.findPendingQueue(PageRequest.of(0, 10)).getContent().get(0);

        assertThat(row.getLatestReason()).isEqualTo(SPAM);
        assertThat(row.getSeverity()).isEqualTo(10);
        assertThat(row.getTargetType()).isEqualTo("VIDEO");
    }

    @Test
    void anUnknownScenarioIsRankedRatherThanBuried() {
        report("v-known", SPAM, hoursAgo(2));
        report("v-unknown", "something a moderator typed by hand", hoursAgo(1));

        assertThat(reportRepository.findPendingQueue(PageRequest.of(0, 10)).getContent())
                .extracting(ReportQueueRow::getSeverity)
                .containsOnly(2);
    }

    @Test
    void theSweepClosesOldLightReportsAndLeavesSeriousOnesAlone() {
        Report oldSpam = report("v-stale", SPAM, daysAgo(40));
        Report oldSelfHarm = report("v-untouchable", SELF_HARM, daysAgo(40));
        Report recentSpam = report("v-recent", SPAM, daysAgo(2));

        int dismissed = reportRepository.dismissStale(Instant.now().minus(30, ChronoUnit.DAYS), 2, 500);

        assertThat(dismissed).isEqualTo(1);
        assertThat(statusOf(oldSpam)).isEqualTo(ReportStatus.DISMISSED);
        assertThat(statusOf(oldSelfHarm)).isEqualTo(ReportStatus.PENDING);
        assertThat(statusOf(recentSpam)).isEqualTo(ReportStatus.PENDING);
    }

    @Test
    void theSweepLeavesATargetManyPeopleReported() {
        Report first = report("v-crowd", SPAM, daysAgo(40));
        report("v-crowd", SPAM, daysAgo(39));
        report("v-crowd", SPAM, daysAgo(38));

        int dismissed = reportRepository.dismissStale(Instant.now().minus(30, ChronoUnit.DAYS), 2, 500);

        assertThat(dismissed).isZero();
        assertThat(statusOf(first)).isEqualTo(ReportStatus.PENDING);
    }

    @Test
    void closingATargetSpareTheReportTheCallerAlreadyResolved() {
        Report resolvedByHand = report("v1", SPAM, hoursAgo(3));
        Report sibling = report("v1", SPAM, hoursAgo(2));
        Report elsewhere = report("v2", SPAM, hoursAgo(1));

        int closed = reportRepository.closePendingFor(ReportTargetType.VIDEO, "v1",
                ReportStatus.RESOLVED, 77L, resolvedByHand.getId(), Instant.now());

        assertThat(closed).isEqualTo(1);
        assertThat(statusOf(sibling)).isEqualTo(ReportStatus.RESOLVED);
        assertThat(statusOf(resolvedByHand)).isEqualTo(ReportStatus.PENDING);
        assertThat(statusOf(elsewhere)).isEqualTo(ReportStatus.PENDING);
    }

    /**
     * The ledger filter, exercised against real Postgres rather than a mock, because
     * {@code (:param IS NULL OR ...)} is the kind of thing that parses, deploys, and then fails
     * at execution time on "could not determine data type of parameter". Four filters means
     * sixteen combinations; these cover all-null, each one alone, and two together.
     *
     * <p>Lives in this class to share its container — a second {@code @Testcontainers} class
     * boots a second Postgres for the same schema.
     */
    @Test
    void anUnfilteredLedgerReturnsEveryReport() {
        report("v1", SPAM, hoursAgo(3));
        report("v2", SELF_HARM, hoursAgo(2));

        assertThat(reportRepository.findFiltered(null, null, null, null, null, null, null, null, PageRequest.of(0, 10)))
                .hasSize(2);
    }

    @Test
    void theLedgerNarrowsToOneReporter() {
        Report mine = filedBy(42L, "v1");
        filedBy(43L, "v2");
        filedBy(44L, "v3");

        assertThat(reportRepository.findFiltered(null, null, null, 42L, null, null, null, null, PageRequest.of(0, 10)))
                .extracting(Report::getId)
                .containsExactly(mine.getId());
    }

    @Test
    void theLedgerCombinesReporterWithStatus() {
        Report pending = filedBy(42L, "v1");
        Report resolved = filedBy(42L, "v2");
        resolved.resolve(99L, ReportStatus.RESOLVED);
        reportRepository.saveAndFlush(resolved);
        filedBy(43L, "v3");

        assertThat(reportRepository.findFiltered(ReportStatus.PENDING, null, null, 42L, null, null, null, null, PageRequest.of(0, 10)))
                .extracting(Report::getId)
                .containsExactly(pending.getId());
    }

    @Test
    void theLedgerFiltersByTargetTypeAndHidesSoftDeletedRows() {
        report("v1", SPAM, hoursAgo(3));
        Report gone = report("v2", SPAM, hoursAgo(2));
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE reports SET deleted_at = now() WHERE id = :id")
                .setParameter("id", gone.getId())
                .executeUpdate();
        entityManager.clear();

        assertThat(reportRepository.findFiltered(null, ReportTargetType.VIDEO, null, null, null, null, null, null, PageRequest.of(0, 10)))
                .hasSize(1);
        assertThat(reportRepository.findFiltered(null, ReportTargetType.USER, null, null, null, null, null, null, PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void theLedgerNarrowsToOneTarget() {
        report("v-wanted", SPAM, hoursAgo(3));
        report("v-wanted", SELF_HARM, hoursAgo(2));
        report("v-other", SPAM, hoursAgo(1));

        assertThat(reportRepository.findFiltered(
                null, ReportTargetType.VIDEO, "v-wanted", null, null, null, null, null, PageRequest.of(0, 10)))
                .hasSize(2);
    }

    @Test
    void theLedgerSearchesTheReasonAndEitherHalfOfACommentTarget() {
        Report spam = report("v1", SPAM, hoursAgo(3));
        Report onComment = report("v2:c77", SELF_HARM, hoursAgo(2));
        report("v3", SELF_HARM, hoursAgo(1));

        assertThat(search("%spam%", null)).extracting(Report::getId).containsExactly(spam.getId());
        assertThat(search("%c77%", null)).extracting(Report::getId).containsExactly(onComment.getId());
    }

    @Test
    void theLedgerFindsOneReportByItsId() {
        report("v1", SPAM, hoursAgo(3));
        Report wanted = report("v2", SPAM, hoursAgo(2));

        assertThat(search("%" + wanted.getId() + "%", wanted.getId()))
                .extracting(Report::getId)
                .containsExactly(wanted.getId());
    }

    /** The service escapes LIKE's wildcards with a backslash; this is the query honouring that. */
    @Test
    void anEscapedWildcardMatchesOnlyItself() {
        Report literal = report("v1", "Says 100% legit", hoursAgo(3));
        report("v2", "Says 1000 legit", hoursAgo(2));

        assertThat(search("%100\\%%", null)).extracting(Report::getId).containsExactly(literal.getId());
    }

    /** From inclusive, to exclusive, the same as the audit log, so consecutive days tile. */
    @Test
    void theLedgerNarrowsToATimeRange() {
        Instant dayStart = Instant.parse("2026-10-06T00:00:00Z");
        Instant dayEnd = Instant.parse("2026-10-07T00:00:00Z");
        report("v1", SPAM, dayStart.minusMillis(1));
        Report first = report("v2", SPAM, dayStart);
        Report last = report("v3", SPAM, dayEnd.minusMillis(1));
        report("v4", SPAM, dayEnd);
        entityManager.clear();

        assertThat(reportRepository.findFiltered(null, null, null, null, null, null, dayStart, dayEnd,
                PageRequest.of(0, 10)))
                .extracting(Report::getId)
                .containsExactlyInAnyOrder(first.getId(), last.getId());
    }

    private List<Report> search(String pattern, Long reportId) {
        entityManager.clear();
        return reportRepository.findFiltered(null, null, null, null, pattern, reportId, null, null,
                PageRequest.of(0, 10)).getContent();
    }

    /** A report by a named reporter — the filter tests turn on who filed it, not when. */
    private Report filedBy(long reporterId, String targetId) {
        return reportRepository.saveAndFlush(Report.builder()
                .reporterId(reporterId)
                .targetType(ReportTargetType.VIDEO)
                .targetId(targetId)
                .reason(SPAM)
                .status(ReportStatus.PENDING)
                .build());
    }

    /**
     * Read back from the database, not from the persistence context: every write under test is a
     * bulk update, which goes round the entities and leaves the cached copies saying PENDING.
     */
    private ReportStatus statusOf(Report report) {
        entityManager.clear();
        return reportRepository.findById(report.getId()).orElseThrow().getStatus();
    }

    /**
     * Saved, then backdated: {@code created_at} is stamped by {@code @PrePersist}, and every
     * ordering and cutoff here depends on it being something other than "now".
     */
    private Report report(String targetId, String reason, Instant createdAt) {
        Report saved = reportRepository.saveAndFlush(Report.builder()
                .reporterId(nextReporterId++)
                .targetType(ReportTargetType.VIDEO)
                .targetId(targetId)
                .reason(reason)
                .status(ReportStatus.PENDING)
                .build());
        entityManager.getEntityManager()
                .createNativeQuery("UPDATE reports SET created_at = :at WHERE id = :id")
                .setParameter("at", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate();
        return saved;
    }

    private static Instant hoursAgo(int hours) {
        return Instant.now().minus(hours, ChronoUnit.HOURS);
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }
}
