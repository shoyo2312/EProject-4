package com.tiktok.searchservice.event.consumer;

import com.tiktok.searchservice.document.ProcessedEventDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.tiktok.searchservice.index.ElasticsearchStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs a consumer's work at most once per eventId.
 *
 * <p>The claim is taken <em>before</em> the work, not after it. Reading
 * {@code existsById} first and writing the record afterwards is wrong twice over here. It leaves
 * the usual check-then-act window that a consumer rebalance walks straight through, so two
 * deliveries both pass and a counter is incremented twice — an increment cannot self-correct the
 * way an assignment does. And on Elasticsearch it is worse than on Mongo: a freshly indexed
 * document is not visible to a read until the index refreshes, one second by default, so a
 * redelivery inside that window <em>always</em> passes the check.
 *
 * <p>{@code OpType.CREATE} has neither problem. It is resolved on the shard against the live
 * version map rather than the refreshed index, so a second create for the same id is rejected
 * with a 409 whether or not a refresh has happened in between.
 *
 * <p>Claiming first moves the risk to the opposite failure: work that throws after the claim
 * would never be retried. So a failure deletes the claim before rethrowing, letting kafka-lib's
 * retry redeliver it. A hard crash between the two can still drop an event — Elasticsearch has
 * no transaction to make the pair atomic — and that is deliberately the smaller risk.
 *
 * <p>The delete goes to the same Elasticsearch that usually just failed the work — a restart, a
 * 429 — so it often fails too. Its failure is attached to the work's as suppressed rather than
 * thrown in its place, because kafka-lib chooses between waiting out an outage and the DLT by
 * the type of what it catches. And the eventId is kept in {@code unreleased}, so the redelivery
 * that finds the claim still there runs the work instead of skipping it as done.
 *
 * <p>ponytail: only this instance remembers. A redelivery that lands on another instance after a
 * rebalance, or on this one after a restart, still finds the claim and skips the event. Recording
 * when the work finished, and letting a claim that never finished expire, would close this and
 * the hard crash above.
 *
 * <p>Mirrors video-service's processor of the same name; user-service does it in one
 * INSERT ... ON CONFLICT DO NOTHING inside a JPA transaction, which needs no compensation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentEventProcessor {

    private static final int CONFLICT = 409;

    private final ElasticsearchOperations elasticsearchOperations;

    /** Claims this instance still holds because releasing them failed. */
    private final Set<String> unreleased = ConcurrentHashMap.newKeySet();

    public void runOnce(String eventId, String eventType, Runnable work) {
        if (!tryClaim(eventId, eventType) && !unreleased.contains(eventId)) {
            log.debug("Skipping already-processed {} eventId={}", eventType, eventId);
            return;
        }

        try {
            work.run();
        } catch (RuntimeException ex) {
            log.error("Releasing claim on {} eventId={} after failure, will be redelivered",
                    eventType, eventId, ex);
            try {
                elasticsearchOperations.delete(eventId, ProcessedEventDocument.class);
                unreleased.remove(eventId);
            } catch (RuntimeException releaseFailure) {
                log.warn("Could not release claim on {} eventId={}, only this instance will run it again",
                        eventType, eventId, releaseFailure);
                unreleased.add(eventId);
                ex.addSuppressed(releaseFailure);
            }
            throw ex;
        }
        unreleased.remove(eventId);
    }

    private boolean tryClaim(String eventId, String eventType) {
        IndexQuery claim = new IndexQueryBuilder()
                .withId(eventId)
                .withObject(ProcessedEventDocument.builder()
                        .id(eventId)
                        .eventType(eventType)
                        .processedAt(Instant.now())
                        .build())
                .withOpType(IndexQuery.OpType.CREATE)
                .build();

        try {
            elasticsearchOperations.index(claim, elasticsearchOperations.getIndexCoordinatesFor(
                    ProcessedEventDocument.class));
            return true;
        } catch (DataAccessException ex) {
            if (ElasticsearchStatus.is(ex, CONFLICT)) {
                return false;
            }
            throw ElasticsearchStatus.classify(ex);
        }
    }
}
