package com.tiktok.videoservice.event.consumer;

import com.tiktok.videoservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a consumer's work at most once per eventId.
 *
 * <p>The claim is taken <em>before</em> the work, not after it. Checking
 * {@code existsByEventId} first and recording the event afterwards leaves a window where two
 * concurrent deliveries — routine during a consumer rebalance — both pass the check and both
 * apply the work. For the {@code $inc} counter consumers that means a permanently wrong
 * likeCount, since an increment cannot self-correct the way an assignment does.
 *
 * <p>Claiming first moves the risk to the opposite failure: work that throws after the claim
 * would never be retried. That is why a failure releases the claim before rethrowing, letting
 * kafka-lib's retry redeliver it. A hard crash between the two can still drop an event — Mongo
 * runs single-node here, so there is no transaction to make the pair atomic. This is
 * deliberately the smaller risk of the two.
 *
 * <p>user-service solves the same problem with a single INSERT ... ON CONFLICT DO NOTHING
 * inside a JPA transaction, which needs no compensation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotentEventProcessor {

    private final ProcessedEventRepository processedEventRepository;

    public void runOnce(String eventId, String eventType, Runnable work) {
        if (!processedEventRepository.tryClaim(eventId, eventType)) {
            log.debug("Skipping already-processed {} eventId={}", eventType, eventId);
            return;
        }

        try {
            work.run();
        } catch (RuntimeException ex) {
            log.error("Releasing claim on {} eventId={} after failure, will be redelivered",
                    eventType, eventId, ex);
            processedEventRepository.releaseClaim(eventId);
            throw ex;
        }
    }
}
