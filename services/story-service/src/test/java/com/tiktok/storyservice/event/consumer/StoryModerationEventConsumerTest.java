package com.tiktok.storyservice.event.consumer;

import com.tiktok.event.story.StoryModerationCompletedEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
import com.tiktok.storyservice.entity.StoryModeration;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.repository.StoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class StoryModerationEventConsumerTest {

    private final StoryRepository storyRepository = mock(StoryRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final StoryModerationEventConsumer consumer =
            new StoryModerationEventConsumer(storyRepository, objectMapper, 24);

    private String verdict(String storyId, ModerationVerdict verdict, Integer duration) {
        return objectMapper.writeValueAsString(StoryModerationCompletedEvent.from(storyId,
                VideoModerationCompletedEvent.of(storyId, verdict, "nsfw", 0.4, 1, 10, 30L, "m", "v1", null), duration));
    }

    @Test
    void approvedBecomesActiveFor24h() {
        when(storyRepository.decide(anyString(), any(), any(), any(), any())).thenReturn(true);

        consumer.onMessage(verdict("s1", ModerationVerdict.APPROVED, 42));

        ArgumentCaptor<Instant> expiresAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<StoryModeration> moderation = ArgumentCaptor.forClass(StoryModeration.class);
        verify(storyRepository).decide(eq("s1"), eq(StoryStatus.ACTIVE), expiresAt.capture(), eq(42), moderation.capture());
        assertThat(expiresAt.getValue()).isCloseTo(Instant.now().plus(24, ChronoUnit.HOURS), within(5, ChronoUnit.SECONDS));
        assertThat(moderation.getValue().getVerdict()).isEqualTo(ModerationVerdict.APPROVED);
        assertThat(moderation.getValue().getMaxScore()).isEqualTo(0.4);
        assertThat(moderation.getValue().getModelVersion()).isEqualTo("v1");
    }

    @Test
    void reviewBecomesHeldKeepingExpiry() {
        when(storyRepository.decide(anyString(), any(), any(), any(), any())).thenReturn(true);

        consumer.onMessage(verdict("s1", ModerationVerdict.REVIEW, null));

        verify(storyRepository).decide(eq("s1"), eq(StoryStatus.HELD), isNull(), isNull(), any());
    }

    @Test
    void rejectedBecomesRejectedKeepingExpiry() {
        when(storyRepository.decide(anyString(), any(), any(), any(), any())).thenReturn(true);

        consumer.onMessage(verdict("s1", ModerationVerdict.REJECTED, 70));

        verify(storyRepository).decide(eq("s1"), eq(StoryStatus.REJECTED), isNull(), eq(70), any());
    }

    @Test
    void redeliveredVerdictChangesNothing() {
        when(storyRepository.decide(anyString(), any(), any(), any(), any())).thenReturn(false);

        assertThatCode(() -> consumer.onMessage(verdict("s1", ModerationVerdict.APPROVED, 5))).doesNotThrowAnyException();

        verify(storyRepository).decide(eq("s1"), any(), any(), any(), any());
        verifyNoMoreInteractions(storyRepository);
    }

    @Test
    void verdictForUnknownStoryIsNoOp() {
        when(storyRepository.decide(eq("ghost"), any(), any(), any(), any())).thenReturn(false);

        assertThatCode(() -> consumer.onMessage(verdict("ghost", ModerationVerdict.REJECTED, null))).doesNotThrowAnyException();

        verifyNoMoreInteractions(ignoreStubs(storyRepository));
    }
}
