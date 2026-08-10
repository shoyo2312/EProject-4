package com.tiktok.authservice.service;

import com.tiktok.authservice.config.RetentionProperties;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.VerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.function.IntSupplier;

/**
 * Deletes auth-service rows that have outlived their purpose.
 *
 * <p>Three tables here only ever grew. Every login and every refresh appends to
 * {@code refresh_tokens}, every OTP to {@code verification_tokens}, every registration to
 * {@code outbox_events}, and nothing removed any of it — roughly a hundred rows per active user
 * per day, kept forever. video-service solved the same problem with a 30-day TTL on
 * {@code processed_events}; Postgres has no TTL, so it takes a job.
 *
 * <p>These are hard deletes, which the rest of the codebase forbids. The rule is about domain
 * records, where "deleted" is a state a user can be shown and an auditor can ask about. These
 * rows are infrastructure — a spent OTP, a token that expired last month, an event Kafka already
 * has — and soft-deleting them would mean setting a flag on rows whose entire problem is that
 * there are too many of them.
 *
 * <p>Safe to run on every replica at once: the deletes are idempotent, and two instances working
 * the same table just split the batches between them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredRecordCleanup {

    private final RefreshTokenRepository refreshTokenRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final RetentionProperties retention;
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "${auth.retention.cron}")
    public void purgeExpiredRecords() {
        Instant now = Instant.now();
        int batchSize = retention.batchSize();

        sweep("refresh tokens", () -> refreshTokenRepository.deleteExpiredBefore(
                now.minus(retention.refreshTokenGrace()), batchSize));
        sweep("verification tokens", () -> verificationTokenRepository.deleteExpiredBefore(
                now.minus(retention.verificationTokenGrace()), batchSize));
        sweep("published outbox events", () -> outboxEventRepository.deletePublishedBefore(
                now.minus(retention.publishedOutboxGrace()), batchSize));
    }

    /**
     * Runs one table's deletion in batches until it stops finding rows or hits the per-run
     * ceiling.
     *
     * <p>Each batch commits on its own, via the template rather than a {@code @Transactional}
     * method on this class — a self-invoked call would not go through the proxy, so the whole
     * sweep would quietly end up in one transaction and hold every lock it took until the last
     * batch. A short batch means the row locks are released while the next one is being found,
     * and a failure halfway through keeps the work already done.
     */
    private void sweep(String what, IntSupplier deleteBatch) {
        int total = 0;

        for (int pass = 0; pass < retention.maxBatchesPerRun(); pass++) {
            Integer deleted = transactionTemplate.execute(status -> deleteBatch.getAsInt());
            total += deleted == null ? 0 : deleted;

            if (deleted == null || deleted < retention.batchSize()) {
                if (total > 0) {
                    log.info("Retention removed {} expired {}", total, what);
                }
                return;
            }
        }

        log.info("Retention removed {} expired {} and stopped at the per-run ceiling — the rest "
                + "go in the next run", total, what);
    }
}
