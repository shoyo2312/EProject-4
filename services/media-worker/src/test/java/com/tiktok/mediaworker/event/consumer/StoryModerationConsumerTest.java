package com.tiktok.mediaworker.event.consumer;

import com.tiktok.event.story.StoryCreatedEvent;
import com.tiktok.event.story.StoryModerationCompletedEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.mediaworker.event.producer.StoryModerationEventProducer;
import com.tiktok.mediaworker.service.ModerationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class StoryModerationConsumerTest {

    private final ModerationService moderationService = mock(ModerationService.class);
    private final StoryModerationEventProducer producer = mock(StoryModerationEventProducer.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final StoryModerationConsumer consumer =
            new StoryModerationConsumer(moderationService, producer, objectMapper);

    @Test
    void captionHoldTurnsApprovalIntoReview() {
        when(moderationService.moderateStory("s1", "stories/7/1.mp4", false)).thenReturn(new ModerationService.StoryVerdict(
                VideoModerationCompletedEvent.of("s1", ModerationVerdict.APPROVED, "nsfw", 0.01, 0, 10, 50L, "m", "v1", null),
                42));
        StoryCreatedEvent event = StoryCreatedEvent.of("s1", 7L, "stories/7/1.mp4", "VIDEO", ModerationVerdict.REVIEW);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        ArgumentCaptor<StoryModerationCompletedEvent> published = ArgumentCaptor.forClass(StoryModerationCompletedEvent.class);
        verify(producer).publish(published.capture());
        assertThat(published.getValue().storyId()).isEqualTo("s1");
        assertThat(published.getValue().verdict()).isEqualTo(ModerationVerdict.REVIEW);
        assertThat(published.getValue().durationSeconds()).isEqualTo(42);
    }

    @Test
    void imageStoryIsScreenedAsAnImage() {
        when(moderationService.moderateStory("s2", "stories/7/2.jpg", true)).thenReturn(new ModerationService.StoryVerdict(
                VideoModerationCompletedEvent.of("s2", ModerationVerdict.REJECTED, "nsfw", 0.99, 1, 1, 50L, "m", "v1", null),
                null));

        consumer.onMessage(objectMapper.writeValueAsString(
                StoryCreatedEvent.of("s2", 7L, "stories/7/2.jpg", "IMAGE", null)));

        ArgumentCaptor<StoryModerationCompletedEvent> published = ArgumentCaptor.forClass(StoryModerationCompletedEvent.class);
        verify(producer).publish(published.capture());
        assertThat(published.getValue().verdict()).isEqualTo(ModerationVerdict.REJECTED);
    }
}
