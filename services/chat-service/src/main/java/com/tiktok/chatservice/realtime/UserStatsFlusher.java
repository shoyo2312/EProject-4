package com.tiktok.chatservice.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Turns everything that moved in the last window into at most one frame per user — see
 * {@code DirtyVideoFlusher}, whose window and batching this mirrors on the same
 * {@code realtime.flush-interval-millis} cadence.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatsFlusher {

    /** Matches both source endpoints' own caps — see UserStatsController and VideoCountsController. */
    private static final int BATCH_SIZE = 50;

    private final DirtyUserRegistry registry;
    private final UserSubscriptionTracker subscriptions;
    private final UserStatsClient statsClient;
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

    private void send(List<String> batch) {
        try {
            for (UserFrame frame : statsClient.fetch(batch)) {
                messaging.convertAndSend(UserTopics.user(Long.valueOf(frame.userId())), frame);
            }
        } catch (RuntimeException e) {
            log.warn("Could not read stats for {} users, skipping this window: {}",
                    batch.size(), e.getMessage());
        }
    }
}
