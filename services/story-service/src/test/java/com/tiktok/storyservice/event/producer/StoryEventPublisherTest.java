package com.tiktok.storyservice.event.producer;

import com.tiktok.event.story.StoryActivatedEvent;
import com.tiktok.event.story.StoryCreatedEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.repository.StoryRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A real OutboxDispatcher over a mocked template: the mark-only-after-ack rule lives there. */
@ExtendWith(MockitoExtension.class)
class StoryEventPublisherTest {

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private KafkaOperations<String, String> kafkaOperations;

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private StoryEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new StoryEventPublisher(storyRepository,
                new OutboxDispatcher(kafkaOperations, Duration.ofSeconds(5)), objectMapper);
    }

    private static Story pending(String id) {
        return Story.pending(id, 7L, "stories/7/" + id + "/media.mp4", "stories/7/" + id + ".mp4",
                StoryMediaType.VIDEO, null, StoryVisibility.PUBLIC, ModerationVerdict.REVIEW, Instant.now());
    }

    private static Story activated(String id) {
        return Story.builder().id(id).userId(7L).status(StoryStatus.ACTIVE).visibility(StoryVisibility.PUBLIC)
                .activatedAt(Instant.now()).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksOnlyAcknowledged() {
        Story acked = pending("1");
        Story refused = pending("2");
        when(storyRepository.findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(acked, refused));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(failed);

        publisher.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations, times(2)).send(captor.capture());
        ProducerRecord<String, String> first = captor.getAllValues().getFirst();
        assertThat(first.topic()).isEqualTo("story.story-events");
        assertThat(first.key()).isEqualTo("1");
        StoryCreatedEvent event = objectMapper.readValue(first.value(), StoryCreatedEvent.class);
        assertThat(event.storyId()).isEqualTo("1");
        assertThat(event.userId()).isEqualTo(7L);
        assertThat(event.mediaKey()).as("the story's copy, not the upload").isEqualTo("stories/7/1/media.mp4");
        assertThat(event.mediaType()).isEqualTo("VIDEO");
        assertThat(event.textVerdict()).isEqualTo(ModerationVerdict.REVIEW);

        verify(storyRepository).markEventPublished(eq("1"), any(Instant.class));
        verify(storyRepository, never()).markEventPublished(eq("2"), any(Instant.class));
    }

    @Test
    void nothingPendingTouchesNoBroker() {
        when(storyRepository.findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of());

        publisher.publishPending();

        verifyNoInteractions(kafkaOperations);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesActivationsKeyedByOwnerAndMarksOnlyAcknowledged() {
        when(storyRepository.findAnnounceable(any(), eq(100))).thenReturn(List.of(activated("1"), activated("2")));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unreachable"));
        when(kafkaOperations.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(null))
                .thenReturn(failed);

        publisher.publishActivated();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations, times(2)).send(captor.capture());
        ProducerRecord<String, String> first = captor.getAllValues().getFirst();
        assertThat(first.topic()).isEqualTo("story.activation-events");
        assertThat(first.key()).isEqualTo("7");
        StoryActivatedEvent event = objectMapper.readValue(first.value(), StoryActivatedEvent.class);
        assertThat(event.storyId()).isEqualTo("1");
        assertThat(event.userId()).isEqualTo(7L);

        verify(storyRepository).markAnnounced(eq("1"), any(Instant.class));
        verify(storyRepository, never()).markAnnounced(eq("2"), any(Instant.class));
    }

    @Test
    void noActivationsTouchesNoBroker() {
        when(storyRepository.findAnnounceable(any(), eq(100))).thenReturn(List.of());

        publisher.publishActivated();

        verifyNoInteractions(kafkaOperations);
    }
}
