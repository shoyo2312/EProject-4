package com.tiktok.analyticsservice.event.consumer;

import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository;
import com.tiktok.event.user.UserFollowChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Follower growth for Studio. A record at a time: follows are a trickle next to watches and likes,
 * which are the streams KafkaBatchConfig exists for. Redelivery writes the same event_id again,
 * which ReplacingMergeTree absorbs and the reads' FINAL hides.
 */
@Component
@RequiredArgsConstructor
public class FollowEventConsumer {

    private final CreatorAnalyticsRepository creatorAnalyticsRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "user.follow-events", groupId = "analytics-service")
    @SneakyThrows
    public void onMessage(String payload) {
        UserFollowChangedEvent event = objectMapper.readValue(payload, UserFollowChangedEvent.class);
        creatorAnalyticsRepository.insertFollow(event.eventId(), event.followerId(), event.followingId(),
                event.followed(), event.occurredAt());
    }
}
