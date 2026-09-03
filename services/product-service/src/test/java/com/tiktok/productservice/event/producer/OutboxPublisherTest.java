package com.tiktok.productservice.event.producer;

import com.tiktok.kafka.outbox.OutboxDispatcher;
import com.tiktok.productservice.entity.OutboxEvent;
import com.tiktok.productservice.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Uses a real {@link OutboxDispatcher} over a mocked template, because the rule under test —
 * a row is marked published only once the broker has acknowledged it — lives in the dispatcher.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaOperations<String, String> kafkaOperations;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OutboxPublisher(
                outboxEventRepository, new OutboxDispatcher(kafkaOperations, Duration.ofSeconds(5)));
    }

    @Test
    void publishPending_marksTheRowOnlyAfterTheBrokerAcknowledges() {
        OutboxEvent event = pending();
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(event));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNotNull();
    }

    /**
     * The failure the migration was for: {@code send()} is async, so a record the broker rejects
     * used to be marked published anyway — and the poll query skips marked rows, so the event was
     * gone for good.
     */
    @Test
    void publishPending_brokerRejects_leavesTheRowPending() {
        OutboxEvent event = pending();
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
                .thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> rejected = new CompletableFuture<>();
        rejected.completeExceptionally(new IllegalStateException("broker refused the record"));
        when(kafkaOperations.send(any(ProducerRecord.class))).thenReturn(rejected);

        publisher.publishPending();

        assertThat(event.getPublishedAt()).isNull();
    }

    private OutboxEvent pending() {
        return OutboxEvent.builder()
                .aggregateType("PRODUCT")
                .aggregateId("1")
                .eventType("ProductCreatedEvent")
                .payload("{}")
                .build();
    }
}
