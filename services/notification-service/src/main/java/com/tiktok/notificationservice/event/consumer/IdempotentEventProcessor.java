package com.tiktok.notificationservice.event.consumer;

import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Runs a consumer's work at most once per eventId.
 *
 * <p>The claim is taken <em>before</em> the work, not after it. Checking
 * {@code existsByEventId} first and recording the event afterwards leaves a window where two
 * concurrent deliveries — routine during a consumer rebalance — both pass the check and both
 * apply the work. Here that means the same "someone liked your video" appearing twice in an
 * inbox and two pushes for one like, with no later event able to correct it.
 *
 * <p>Claiming first moves the risk to the opposite failure: work that throws after the claim
 * would never be retried. That is why a failure releases the claim before rethrowing, letting
 * kafka-lib's error handler redeliver it. A hard crash between the two can still drop an event —
 * Mongo runs single-node here, so there is no transaction to make the pair atomic. For a
 * notification that is the smaller of the two risks: one missed badge beats a duplicated one.
 *
 * <p>Copied in shape from video-service's processor of the same name; the two services own
 * separate collections, so there is nothing to share but the reasoning.
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
