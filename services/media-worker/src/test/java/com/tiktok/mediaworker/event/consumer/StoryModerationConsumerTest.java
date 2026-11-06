package com.tiktok.mediaworker.event.consumer;

import com.tiktok.event.story.StoryCreatedEvent;
import com.tiktok.event.story.StoryModerationCompletedEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.mediaworker.event.producer.StoryModerationEventProducer;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.mediaworker.service.MediaRejectedException;
import com.tiktok.mediaworker.service.ModerationService;
import com.tiktok.mediaworker.service.StoryRenderer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StoryModerationConsumerTest {

    private final ModerationService moderationService = mock(ModerationService.class);
    private final StoryModerationEventProducer producer = mock(StoryModerationEventProducer.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final StoryRenderer renderer = mock(StoryRenderer.class);
    private final StoryModerationConsumer consumer =
            new StoryModerationConsumer(moderationService, producer, objectMapper, renderer);

    private static final VideoPublishedEvent.Edit SONG = new VideoPublishedEvent.Edit(1, List.of(), null, List.of(),
            new VideoPublishedEvent.Edit.Music("https://prod-1.storage.jamendo.com/download/42", 0, 1, 1));

    /** What is screened is what was rendered, and story-service is told to serve that. */
    @Test
    void anEditedStoryIsScreenedAsRendered() throws Exception {
        when(renderer.render("s3", "stories/7/s3/media.jpg", true, SONG, 8.0))
                .thenReturn(new StoryRenderer.Rendered("stories/7/s3/rendered.mp4", false));
        when(moderationService.moderateStory("s3", "stories/7/s3/rendered.mp4", false)).thenReturn(
                new ModerationService.StoryVerdict(VideoModerationCompletedEvent.of("s3", ModerationVerdict.APPROVED,
                        "nsfw", 0.01, 0, 10, 50L, "m", "v1", null), 8));

        consumer.onMessage(objectMapper.writeValueAsString(
                StoryCreatedEvent.of("s3", 7L, "stories/7/s3/media.jpg", "IMAGE", null, SONG, 8.0)));

        ArgumentCaptor<StoryModerationCompletedEvent> published = ArgumentCaptor.forClass(StoryModerationCompletedEvent.class);
        verify(producer).publish(published.capture());
        assertThat(published.getValue().verdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(published.getValue().mediaKey()).isEqualTo("stories/7/s3/rendered.mp4");
        assertThat(published.getValue().mediaType()).isEqualTo("VIDEO");
        assertThat(published.getValue().durationSeconds()).isEqualTo(8);
    }

    /** Never the story as posted in place of its edit: one that cannot be rendered is refused. */
    @Test
    void aStoryWhoseEditCannotBeAppliedIsRefused() throws Exception {
        when(renderer.render(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenThrow(new MediaRejectedException("The edit keeps nothing of the story."));

        consumer.onMessage(objectMapper.writeValueAsString(
                StoryCreatedEvent.of("s4", 7L, "stories/7/s4/media.mp4", "VIDEO", null, SONG, null)));

        ArgumentCaptor<StoryModerationCompletedEvent> published = ArgumentCaptor.forClass(StoryModerationCompletedEvent.class);
        verify(producer).publish(published.capture());
        assertThat(published.getValue().verdict()).isEqualTo(ModerationVerdict.REJECTED);
        assertThat(published.getValue().mediaKey()).isNull();
        verifyNoInteractions(moderationService);
        verify(renderer, times(1)).render(anyString(), anyString(), anyBoolean(), any(), any());
    }

    /** Storage trouble gets a second try before the story is given up on. */
    @Test
    void aPassingFailureIsTriedAgain() throws Exception {
        when(renderer.render(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenThrow(new java.io.IOException("connection reset"))
                .thenReturn(new StoryRenderer.Rendered("stories/7/s5/rendered.jpg", true));
        when(moderationService.moderateStory("s5", "stories/7/s5/rendered.jpg", true)).thenReturn(
                new ModerationService.StoryVerdict(VideoModerationCompletedEvent.of("s5", ModerationVerdict.APPROVED,
                        "nsfw", 0.01, 0, 1, 50L, "m", "v1", null), null));

        consumer.onMessage(objectMapper.writeValueAsString(
                StoryCreatedEvent.of("s5", 7L, "stories/7/s5/media.jpg", "IMAGE", null, SONG, null)));

        ArgumentCaptor<StoryModerationCompletedEvent> published = ArgumentCaptor.forClass(StoryModerationCompletedEvent.class);
        verify(producer).publish(published.capture());
        assertThat(published.getValue().mediaType()).isEqualTo("IMAGE");
    }

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
