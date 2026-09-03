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
 * <p>Mirrors video-service's processor of the same name; user-service does it in one
 * INSERT ... ON CONFLICT DO NOTHING inside a JPA transaction, which needs no compensation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentEventProcessor {

    private static final int CONFLICT = 409;

    private final ElasticsearchOperations elasticsearchOperations;

    public void runOnce(String eventId, String eventType, Runnable work) {
        if (!tryClaim(eventId, eventType)) {
            log.debug("Skipping already-processed {} eventId={}", eventType, eventId);
            return;
        }

        try {
            work.run();
        } catch (RuntimeException ex) {
            log.error("Releasing claim on {} eventId={} after failure, will be redelivered",
                    eventType, eventId, ex);
            elasticsearchOperations.delete(eventId, ProcessedEventDocument.class);
            throw ex;
        }
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
            throw ex;
        }
    }
}
