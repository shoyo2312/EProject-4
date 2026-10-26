package com.tiktok.userservice.event.producer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.DomainEvent;
import com.tiktok.event.user.FollowRequestedEvent;
import com.tiktok.event.user.UserFollowChangedEvent;
import com.tiktok.event.user.UserPrivacyChangedEvent;
import com.tiktok.event.user.WeeklyScreenTimeSummaryEvent;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Writes user-service's announcements to the outbox in the caller's transaction; {@link OutboxPublisher}
 * sends them to Kafka afterwards, each aggregate type to its own topic.
 *
 * <p>It used to send straight to the broker and block on the ack, inside the {@code @Transactional}
 * follow. That held a pooled connection for the whole round trip, and a broker that was down failed
 * a follow the database had already accepted — an event lost or a request lost, depending on which
 * side of the commit the failure landed. A row in the same transaction has neither problem: it
 * commits with the edge, and a broker outage only delays delivery.
 */
@Component
@RequiredArgsConstructor
public class UserEventPublisher {

    static final String FOLLOW_AGGREGATE = "USER_FOLLOW";
    static final String FOLLOW_REQUEST_AGGREGATE = "FOLLOW_REQUEST";
    static final String PRIVACY_AGGREGATE = "USER_PRIVACY";
    static final String SCREEN_TIME_SUMMARY_AGGREGATE = "SCREEN_TIME_SUMMARY";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishFollowChanged(Long followerId, Long followingId, boolean followed) {
        publishFollowChanged(followerId, followingId, followed, false);
    }

    /** @param requestApproved the edge is a follow request its owner approved — the requester is told */
    public void publishFollowChanged(Long followerId, Long followingId, boolean followed,
                                     boolean requestApproved) {
        // Keyed by the followed user, so every change to one user's follower count keeps its order
        // on a single partition.
        save(FOLLOW_AGGREGATE, followingId,
                UserFollowChangedEvent.of(followerId, followingId, followed, requestApproved));
    }

    public void publishFollowRequested(Long requesterId, Long targetId) {
        save(FOLLOW_REQUEST_AGGREGATE, targetId, FollowRequestedEvent.of(requesterId, targetId));
    }

    public void publishPrivacyChanged(Long userId, boolean privateAccount) {
        // Keyed by the account, so a quick private-public-private reaches video-service in order.
        save(PRIVACY_AGGREGATE, userId, UserPrivacyChangedEvent.of(userId, privateAccount));
    }

    /** Must be called in the transaction that claimed the week, so the claim and the event commit together. */
    public void publishWeeklyScreenTimeSummary(Long userId, LocalDate weekStart, long totalSeconds) {
        save(SCREEN_TIME_SUMMARY_AGGREGATE, userId,
                WeeklyScreenTimeSummaryEvent.of(userId, weekStart.toString(), totalSeconds));
    }

    @SneakyThrows
    private void save(String aggregateType, Long key, DomainEvent event) {
        outboxEventRepository.save(OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(String.valueOf(key))
                .eventType(event.getClass().getSimpleName())
                .payload(objectMapper.writeValueAsString(event))
                .build());
    }
}
