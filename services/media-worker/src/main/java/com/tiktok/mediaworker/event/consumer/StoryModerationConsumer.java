package com.tiktok.mediaworker.event.consumer;

import com.tiktok.event.story.StoryCreatedEvent;
import com.tiktok.event.story.StoryModerationCompletedEvent;
import com.tiktok.mediaworker.event.producer.StoryModerationEventProducer;
import com.tiktok.mediaworker.service.CaptionVerdict;
import com.tiktok.mediaworker.service.ModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Screens a newly posted story and answers story-service with the verdict.
 *
 * <p>No quarantine move, unlike videos: story media sits under {@code stories/}, which was never
 * readable anonymously, so holding a story back is story-service no longer handing out signed URLs.
 *
 * <p>No inbox — this worker keeps no state. A redelivered event re-scores the same file; the
 * durable write is story-service's, where only the first verdict moves a story out of
 * PENDING_MODERATION.
 */
@Component
@RequiredArgsConstructor
public class StoryModerationConsumer {

    private final ModerationService moderationService;
    private final StoryModerationEventProducer eventProducer;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "story.story-events", groupId = "media-worker-story-moderation")
    public void onMessage(String payload) {
        StoryCreatedEvent event = objectMapper.readValue(payload, StoryCreatedEvent.class);

        // moderateStory() never throws, for the same reason moderate() does: a story without a
        // verdict stays PENDING_MODERATION until its ceiling removes it, unseen by anyone.
        ModerationService.StoryVerdict verdict = moderationService.moderateStory(
                event.storyId(), event.mediaKey(), "IMAGE".equals(event.mediaType()));

        eventProducer.publish(StoryModerationCompletedEvent.from(event.storyId(),
                CaptionVerdict.combine(verdict.frames(), event.textVerdict()), verdict.durationSeconds()));
    }
}
