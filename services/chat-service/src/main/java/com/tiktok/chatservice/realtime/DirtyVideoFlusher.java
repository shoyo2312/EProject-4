package com.tiktok.chatservice.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns everything that moved in the last window into at most one frame per video.
 *
 * <p>This is the anti-spam layer that does not care where the spam came from: a viewer hammering
 * the like button, a hot video taking two hundred likes a second, and a redelivered batch of
 * Kafka records all collapse into the same two frames a second.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirtyVideoFlusher {

    /** interaction-service caps its batch endpoint at 50; asking for more silently drops the rest. */
    private static final int BATCH_SIZE = 50;

    private final DirtyVideoRegistry registry;
    private final SubscriptionTracker subscriptions;
    private final InteractionCountsClient countsClient;
    private final SimpMessagingTemplate messaging;

    @Scheduled(fixedDelayString = "${realtime.flush-interval-millis}")
    public void flush() {
        List<String> watched = registry.drain().stream()
                .filter(subscriptions::isWatched)
                .toList();

        for (int from = 0; from < watched.size(); from += BATCH_SIZE) {
            send(watched.subList(from, Math.min(from + BATCH_SIZE, watched.size())));
        }
    }

    /**
     * A failed batch is dropped rather than retried or rethrown. Retrying would push the next
     * window's work behind it, and throwing out of a @Scheduled method only fills the log — while
     * the cost of dropping it is one stale render, corrected by the next event on that video, on
     * top of the REST value the client already has.
     */
    private void send(List<String> batch) {
        try {
            for (VideoFrame frame : countsClient.fetch(batch)) {
                messaging.convertAndSend(VideoTopics.video(frame.videoId()), frame);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read counters for {} videos, skipping this window: {}",
                    batch.size(), e.getMessage());
        }
    }
}
