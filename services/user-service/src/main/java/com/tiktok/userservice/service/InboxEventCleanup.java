package com.tiktok.userservice.service;

import com.tiktok.userservice.config.RetentionProperties;
import com.tiktok.userservice.repository.InboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Deletes {@code inbox_events} claims that have outlived their purpose.
 *
 * <p>The table only ever grew: every Kafka event this service consumes appends one row and
 * nothing removed any of it. It sits on the hot path — {@code tryClaim} runs before every event
 * is processed — so an unbounded table is not merely disk, it is a unique index that keeps
 * getting deeper under the one insert that cannot be skipped. auth-service has the same job for
 * its own tables, and video-service gets the same effect from a TTL index; Postgres has no TTL,
 * so here it takes a job.
 *
 * <p>These are hard deletes, which the rest of the codebase forbids. The rule is about domain
 * records, where "deleted" is a state a user can be shown and an auditor can ask about. A claim
 * on an event Kafka has already dropped is infrastructure, and soft-deleting it would mean
 * setting a flag on rows whose entire problem is that there are too many of them.
 *
 * <p>Safe to run on every replica at once: the deletes are idempotent, and two instances working
 * the table just split the batches between them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxEventCleanup {

    private final InboxEventRepository inboxEventRepository;
    private final RetentionProperties retention;
    private final TransactionTemplate transactionTemplate;

    /**
     * Runs the deletion in batches until it stops finding rows or hits the per-run ceiling.
     *
     * <p>Each batch commits on its own, via the template rather than a {@code @Transactional}
     * method on this class — a self-invoked call would not go through the proxy, so the whole
     * sweep would quietly end up in one transaction and hold every lock it took until the last
     * batch. That matters more here than in a typical job: the locks would be on the table every
     * consumer has to insert into to claim its event, so a long sweep would stall consumption.
     */
    @Scheduled(cron = "${user.retention.cron}")
    public void purgeProcessedEvents() {
        Instant cutoff = Instant.now().minus(retention.processedEventGrace());
        int total = 0;

        for (int pass = 0; pass < retention.maxBatchesPerRun(); pass++) {
            Integer deleted = transactionTemplate.execute(
                    status -> inboxEventRepository.deleteProcessedBefore(cutoff, retention.batchSize()));
            total += deleted == null ? 0 : deleted;

            if (deleted == null || deleted < retention.batchSize()) {
                if (total > 0) {
                    log.info("Retention removed {} processed inbox events", total);
                }
                return;
            }
        }

        log.info("Retention removed {} processed inbox events and stopped at the per-run ceiling — "
                + "the rest go in the next run", total);
    }
}
