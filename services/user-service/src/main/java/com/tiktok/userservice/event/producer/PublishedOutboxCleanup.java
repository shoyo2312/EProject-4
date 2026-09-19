package com.tiktok.userservice.event.producer;

import com.tiktok.userservice.config.RetentionProperties;
import com.tiktok.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Deletes outbox rows Kafka already has. Without it the table only grows: every follow and
 * unfollow appends a row and nothing ever removed one.
 *
 * <p>Hard deletes, which §6 otherwise forbids — the same carve-out auth-service's
 * {@code ExpiredRecordCleanup} documents. These are infrastructure rows whose whole problem is
 * that there are too many of them; a {@code deletedAt} flag would not fix that. The follow edges
 * themselves live in {@code user_follows}, which is never swept.
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
    private final RetentionProperties retention;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "${user.retention.cron}")
    public void purgePublishedEvents() {
        Instant cutoff = Instant.now().minus(retention.publishedOutboxGrace());
        int total = 0;

        for (int pass = 0; pass < retention.maxBatchesPerRun(); pass++) {
            Integer deleted = transactionTemplate.execute(
                    status -> outboxEventRepository.deletePublishedBefore(cutoff, retention.batchSize()));
            total += deleted == null ? 0 : deleted;

            if (deleted == null || deleted < retention.batchSize()) {
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
