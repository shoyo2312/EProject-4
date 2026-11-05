package com.tiktok.userservice.service;

import com.tiktok.event.user.UserFollowChangedEvent;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.repository.UserFollowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Replays the live follow graph into analytics once. ClickHouse's follow_events only holds follows
 * made since its consumer shipped, so every older follower would read as a non-follower in a
 * creator's viewer breakdown. One event per live edge, dated by the edge, with an event id
 * derived from the edge id — ReplacingMergeTree collapses a re-run, so running it twice is safe
 * (the outbox carries the duplicates; ClickHouse drops them).
 *
 * <p>Its own topic, read by analytics-service alone: on user.follow-events the same replay would
 * become thousands of "X followed you" notifications and chat side effects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowGraphBackfillService {

    static final int PAGE_SIZE = 500;

    private final UserFollowRepository userFollowRepository;
    private final UserEventPublisher userEventPublisher;
    private final TransactionTemplate transactionTemplate;

    /** @return how many edges were written to the outbox */
    public long publishAll() {
        long published = 0;
        long after = Long.MIN_VALUE;
        while (true) {
            long from = after;
            // One short transaction per page: keyset on id, so a graph of any size neither loads
            // at once nor holds one connection for the whole run.
            List<UserFollow> page = transactionTemplate.execute(status -> {
                List<UserFollow> edges = userFollowRepository
                        .findByIdGreaterThanAndDeletedAtIsNullOrderByIdAsc(from, PageRequest.of(0, PAGE_SIZE));
                edges.forEach(edge -> userEventPublisher.publishFollowBackfill(new UserFollowChangedEvent(
                        "backfill-follow-" + edge.getId(), edge.getCreatedAt(),
                        edge.getFollowerId(), edge.getFollowingId(), true, false, null)));
                return edges;
            });
            if (page == null || page.isEmpty()) {
                break;
            }
            published += page.size();
            after = page.get(page.size() - 1).getId();
        }
        log.info("Follow graph backfill wrote {} edges to the outbox", published);
        return published;
    }
}
