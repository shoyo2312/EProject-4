package com.tiktok.adminservice.repository;

import com.tiktok.adminservice.entity.Report;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
