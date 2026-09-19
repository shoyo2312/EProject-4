package com.tiktok.adminservice.event.producer;

import com.tiktok.adminservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Deletes outbox rows Kafka already has. Without it the table only grows: every ban, takedown and
 * comment removal appends a row and nothing ever removed one.
 *
 * <p>Hard deletes, which §6 otherwise forbids — the same carve-out auth-service's
 * {@code ExpiredRecordCleanup} documents. These are infrastructure rows whose whole problem is
 * that there are too many of them; a {@code deletedAt} flag would not fix that. The audit trail
 * lives in {@code moderation_actions}, which is never swept.
 *
 * <p>Each batch commits through the template rather than a {@code @Transactional} method on this
 * class: a self-invoked call skips the proxy, which would put the whole sweep in one transaction
 * holding every lock it took until the last batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PublishedOutboxCleanup {

    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${admin.retention.published-outbox-grace}")
    private Duration publishedOutboxGrace;

    @Value("${admin.retention.batch-size}")
    private int batchSize;

    @Value("${admin.retention.max-batches-per-run}")
    private int maxBatchesPerRun;

    @Scheduled(cron = "${admin.retention.cron}")
    public void purgePublishedEvents() {
        Instant cutoff = Instant.now().minus(publishedOutboxGrace);
        int total = 0;

        for (int pass = 0; pass < maxBatchesPerRun; pass++) {
            Integer deleted = transactionTemplate.execute(
                    status -> outboxEventRepository.deletePublishedBefore(cutoff, batchSize));
            total += deleted == null ? 0 : deleted;

            if (deleted == null || deleted < batchSize) {
                if (total > 0) {
                    log.info("Retention removed {} published outbox events", total);
                }
                return;
            }
        }

        log.info("Retention removed {} published outbox events and stopped at the per-run "
                + "ceiling — the rest go in the next run", total);
    }
}
