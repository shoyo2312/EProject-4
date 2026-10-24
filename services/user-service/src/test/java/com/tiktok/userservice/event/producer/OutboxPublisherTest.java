package com.tiktok.userservice.event.producer;

import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.userservice.entity.OutboxEvent;
import com.tiktok.userservice.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaOperations<String, String> kafkaOperations;

    private OutboxPublisher publisher() {
        return new OutboxPublisher(outboxEventRepository,
                new OutboxDispatcher(kafkaOperations, Duration.ofSeconds(1)));
    }

    /**
     * One event type per topic is what lets every consumer parse without routing, so a row on the
     * wrong topic is read as the wrong event — silently.
     */
    @Test
    void eachAggregateTypeGoesToItsOwnTopic_keyedByItsAggregate() {
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(
                row(1L, "USER_FOLLOW", "2"),
                row(2L, "FOLLOW_REQUEST", "3"),
                row(3L, "USER_PRIVACY", "4"),
                row(4L, "SCREEN_TIME_SUMMARY", "5")));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher().publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations, times(4)).send(sent.capture());
        assertThat(sent.getAllValues())
                .extracting(ProducerRecord::topic, ProducerRecord::key)
                .containsExactly(
                        tuple("user.follow-events", "2"),
                        tuple("user.follow-request-events", "3"),
                        tuple("user.privacy-events", "4"),
                        tuple("user.screen-time-summary-events", "5"));
        verify(outboxEventRepository).markPublished(eq(List.of(1L, 2L, 3L, 4L)), any(Instant.class));
    }

    /**
     * An instance older than the row it reads — mid-deploy, say — cannot know where it goes. Left
     * pending, a newer instance sends it; guessed, it would land on a topic whose consumer misreads it.
     */
    @Test
    void aRowWithAnUnknownAggregateType_staysPending() {
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(row(1L, "SOMETHING_NEW", "2")));

        publisher().publishPending();

        verifyNoInteractions(kafkaOperations);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    private static OutboxEvent row(Long id, String aggregateType, String aggregateId) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType("Whatever")
                .payload("{}")
                .build();
    }
}
