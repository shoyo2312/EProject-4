package com.tiktok.storyservice.event.consumer;

import com.tiktok.event.story.StoryModerationCompletedEvent;
import com.tiktok.storyservice.entity.StoryModeration;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.repository.StoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * Turns media-worker's verdict into a status. APPROVED starts the story's display window; REVIEW
 * (the model was unsure, or could not run) and REJECTED hide it until the ceiling set at create
 * removes it.
 *
 * <p>No inbox. The only write is {@code decide}, an update conditional on the story still being
 * PENDING_MODERATION, so that condition is the claim: a redelivered verdict, a second verdict from
 * a redelivered created event, or a verdict for a story deleted or already expired matches nothing
 * and changes nothing.
 */
@Slf4j
@Component
public class StoryModerationEventConsumer {

    private final StoryRepository storyRepository;
    private final ObjectMapper objectMapper;
    private final Duration displayWindow;

    public StoryModerationEventConsumer(StoryRepository storyRepository, ObjectMapper objectMapper,
                                        @Value("${story.ttl-hours}") long ttlHours) {
        this.storyRepository = storyRepository;
        this.objectMapper = objectMapper;
        this.displayWindow = Duration.ofHours(ttlHours);
    }

    @KafkaListener(topics = "media.story-moderation-events", groupId = "story-service")
    public void onMessage(String payload) {
        StoryModerationCompletedEvent event = objectMapper.readValue(payload, StoryModerationCompletedEvent.class);
        Instant now = Instant.now();

        StoryStatus status = switch (event.verdict()) {
            case APPROVED -> StoryStatus.ACTIVE;
            case REVIEW -> StoryStatus.HELD;
            case REJECTED -> StoryStatus.REJECTED;
        };
        StoryModeration moderation = StoryModeration.builder()
                .verdict(event.verdict())
                .label(event.label())
                .maxScore(event.maxScore())
                .suspiciousFrames(event.suspiciousFrames())
                .totalFrames(event.totalFrames())
                .model(event.model())
                .modelVersion(event.modelVersion())
                .reason(event.reason())
                .decidedAt(now)
                .build();

        boolean decided = storyRepository.decide(event.storyId(), status,
                status == StoryStatus.ACTIVE ? now.plus(displayWindow) : null, event.durationSeconds(), moderation);
        if (decided) {
            log.info("Story {} moderated as {}", event.storyId(), status);
        } else {
            log.debug("Verdict for story {} matched nothing — already decided, deleted or expired", event.storyId());
        }
    }
}
