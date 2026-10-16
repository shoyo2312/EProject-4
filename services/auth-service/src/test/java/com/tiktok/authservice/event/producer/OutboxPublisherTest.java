package com.tiktok.authservice.event.producer;

import com.tiktok.authservice.entity.OutboxEvent;
import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.kafka.outbox.OutboxDispatcher;
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
     * Consumers of auth.user-events read every message as a registration, so a rename sent there
     * would be taken, silently, for a second signup of the same account.
     */
    @Test
    void registrationsAndRenames_goToTheirOwnTopics_keyedByAccount() {
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(
                row(1L, "USER", "7"),
                row(2L, "USERNAME", "7")));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher().publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations, times(2)).send(sent.capture());
        assertThat(sent.getAllValues())
                .extracting(ProducerRecord::topic, ProducerRecord::key)
                .containsExactly(
                        tuple("auth.user-events", "7"),
                        tuple("auth.username-events", "7"));
        verify(outboxEventRepository).markPublished(eq(List.of(1L, 2L)), any(Instant.class));
    }

    /**
     * A new account's picture rides the outbox behind its registration, but media-worker listens
     * only where the direct sends go — so that is where it has to end up, in the same poll.
     */
    @Test
    void aNewAccountsAvatar_followsItsRegistrationOntoTheDirectSendsTopic() {
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(
                row(1L, "USER", "7"),
                row(2L, "SOCIAL_AVATAR", "7")));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher().publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaOperations, times(2)).send(sent.capture());
        assertThat(sent.getAllValues())
                .extracting(ProducerRecord::topic, ProducerRecord::key)
                .containsExactly(
                        tuple("auth.user-events", "7"),
                        tuple("auth.social-avatar-events", "7"));
    }

    @Test
    void aRowWithAnUnknownAggregateType_staysPending() {
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(row(1L, "SOMETHING_NEW", "7")));

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
