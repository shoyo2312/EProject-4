package com.tiktok.adminservice.service;

import com.tiktok.adminservice.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Closes reports nobody got to. A queue that only grows is a queue nobody reads: the reports that
 * matter sink under months of single spam flags, and "247 pending" stops meaning anything.
 *
 * <p>Deliberately narrow. A report is swept only when all three hold — it is older than the
 * grace, its scenario is marked {@code auto_expire} in {@code report_reason_weights}, and its
 * target carries no more than {@code max-reports-per-target} standing reports. The scenario flag
 * is the important one: self-harm, violence, sexual content and doxxing never age out, however
 * long the backlog is. If those are what is left after a sweep, that is the correct queue.
 *
 * <p>Nothing is deleted and nothing is enforced. The rows become DISMISSED with
 * {@code resolved_by} NULL, which is what tells the console — and anyone auditing later — that
 * the service closed them rather than an admin. No moderation action is written, because no
 * decision was made about the target.
 *
 * <p>Each batch commits through the template rather than a {@code @Transactional} method on this
 * class: a self-invoked call skips the proxy, which would put the whole sweep in one transaction
 * holding every lock it took until the last batch. Same shape as {@code PublishedOutboxCleanup}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleReportSweep {

    private final ReportRepository reportRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${admin.stale-reports.grace}")
    private final Duration grace;

    @Value("${admin.stale-reports.max-reports-per-target}")
    private final int maxReportsPerTarget;

    @Value("${admin.stale-reports.batch-size}")
    private final int batchSize;

    @Value("${admin.stale-reports.max-batches-per-run}")
    private final int maxBatchesPerRun;

    @Scheduled(cron = "${admin.stale-reports.cron}")
    public void dismissStaleReports() {
        Instant cutoff = Instant.now().minus(grace);
        int total = 0;

        for (int pass = 0; pass < maxBatchesPerRun; pass++) {
            Integer dismissed = transactionTemplate.execute(
                    status -> reportRepository.dismissStale(cutoff, maxReportsPerTarget, batchSize));
            total += dismissed == null ? 0 : dismissed;

            if (dismissed == null || dismissed < batchSize) {
                if (total > 0) {
                    log.info("Auto-dismissed {} reports older than {}", total, grace);
                }
                return;
            }
        }

        log.info("Auto-dismissed {} reports and stopped at the per-run ceiling — the rest go in "
                + "the next run", total);
    }
}
