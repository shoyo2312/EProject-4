package com.tiktok.mediaworker.event.consumer;

import com.tiktok.event.story.StoryCreatedEvent;
import com.tiktok.event.story.StoryModerationCompletedEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.mediaworker.event.producer.StoryModerationEventProducer;
import com.tiktok.mediaworker.service.CaptionVerdict;
import com.tiktok.mediaworker.service.MediaRejectedException;
import com.tiktok.mediaworker.service.ModerationService;
import com.tiktok.mediaworker.service.StoryRenderer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders a newly posted story's edits, screens what was rendered, and answers story-service with
 * the verdict and, for an edited story, where the rendered media is.
 *
 * <p>No quarantine move, unlike videos: story media sits under {@code stories/}, which was never
 * readable anonymously, so holding a story back is story-service no longer handing out signed URLs.
 *
 * <p>No inbox — this worker keeps no state. A redelivered event renders and scores the same file
 * again; the durable write is story-service's, where only the first verdict moves a story out of
 * PENDING_MODERATION.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoryModerationConsumer {

    /** Storage or ffmpeg trouble that may pass gets this many tries before the story is refused. */
    static final int RENDER_ATTEMPTS = 2;

    private final ModerationService moderationService;
    private final StoryModerationEventProducer eventProducer;
    private final ObjectMapper objectMapper;
    private final StoryRenderer storyRenderer;

    @KafkaListener(topics = "story.story-events", groupId = "media-worker-story-moderation")
    public void onMessage(String payload) {
        StoryCreatedEvent event = objectMapper.readValue(payload, StoryCreatedEvent.class);
        boolean image = "IMAGE".equals(event.mediaType());

        StoryRenderer.Rendered rendered;
        try {
            rendered = render(event, image);
        } catch (Exception e) {
            // Never the story as posted in place of its edit: the cuts may be exactly what its owner
            // chose not to show. A story that cannot be rendered is refused, and says why.
            log.warn("Could not render story {}: {}", event.storyId(), e.getMessage());
            eventProducer.publish(StoryModerationCompletedEvent.from(event.storyId(),
                    VideoModerationCompletedEvent.of(event.storyId(), ModerationVerdict.REJECTED, null, 0.0, 0, 0,
                            0L, null, null, "The story's edits could not be applied"), null));
            return;
        }

        String mediaKey = rendered == null ? event.mediaKey() : rendered.mediaKey();
        boolean screenedAsImage = rendered == null ? image : rendered.image();
        // moderateStory() never throws, for the same reason moderate() does: a story without a
        // verdict stays PENDING_MODERATION until its ceiling removes it, unseen by anyone.
        ModerationService.StoryVerdict verdict = moderationService.moderateStory(
                event.storyId(), mediaKey, screenedAsImage);

        VideoModerationCompletedEvent combined = CaptionVerdict.combine(verdict.frames(), event.textVerdict());
        eventProducer.publish(rendered == null
                ? StoryModerationCompletedEvent.from(event.storyId(), combined, verdict.durationSeconds())
                : StoryModerationCompletedEvent.from(event.storyId(), combined, verdict.durationSeconds(),
                        rendered.mediaKey(), rendered.image() ? "IMAGE" : "VIDEO"));
    }

    private StoryRenderer.Rendered render(StoryCreatedEvent event, boolean image) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return storyRenderer.render(event.storyId(), event.mediaKey(), image, event.edit(),
                        event.photoSeconds());
            } catch (MediaRejectedException e) {
                throw e;
            } catch (Exception e) {
                if (attempt >= RENDER_ATTEMPTS) {
                    throw e;
                }
                log.info("Rendering story {} failed on attempt {}, trying again: {}", event.storyId(), attempt,
                        e.getMessage());
            }
        }
    }
}
