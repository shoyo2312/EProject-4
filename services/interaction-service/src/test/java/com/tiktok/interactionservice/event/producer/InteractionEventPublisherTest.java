package com.tiktok.interactionservice.event.producer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.interactionservice.exception.EventNotPublishedException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InteractionEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private InteractionEventPublisher publisher() {
        return new InteractionEventPublisher(kafkaTemplate, new ObjectMapper());
    }

    /**
     * The broker says no through the future, not through send(). That answer has to reach the
     * caller unchecked, or its undo — which catches RuntimeException — never runs.
     */
    @Test
    void aRecordTheBrokerRefusesFailsThePublishUnchecked() {
        KafkaException refusal = new KafkaException("not enough replicas");
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(refusal));

        assertThatThrownBy(() -> publisher().publishLike(1L, 2L, true))
                .isInstanceOf(EventNotPublishedException.class)
                .hasCause(refusal);
    }

    @Test
    void aSendThatThrowsFailsThePublishTheSameWay() {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new KafkaException("metadata not available"));

        assertThatThrownBy(() -> publisher().publishSave(1L, 2L, true))
                .isInstanceOf(EventNotPublishedException.class);
    }

    /**
     * What makes undoing the write safe. The producer keeps retrying a record until its delivery
     * timeout is up, so a publisher that stops waiting sooner undoes interactions the broker then
     * takes. That is a property of two files, the yml and the publisher, so it is checked here
     * rather than trusted to a comment in each.
     */
    @Test
    void theProducerGivesItsVerdictBeforeThePublisherStopsWaiting() throws Exception {
        Map<String, String> producer = new Binder(ConfigurationPropertySources.from(
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))))
                .bind("spring.kafka.producer.properties", Bindable.mapOf(String.class, String.class))
                .orElseThrow(() -> new AssertionError("application.yml sets no producer properties"));

        Duration delivery = Duration.ofMillis(Long.parseLong(producer.get("delivery.timeout.ms")));
        Duration request = Duration.ofMillis(Long.parseLong(producer.get("request.timeout.ms")));
        Duration linger = Duration.ofMillis(Long.parseLong(producer.getOrDefault("linger.ms", "0")));

        assertThat(delivery).isLessThan(InteractionEventPublisher.ACK_BACKSTOP);
        // The producer refuses to start when this does not hold.
        assertThat(request.plus(linger)).isLessThanOrEqualTo(delivery);
        assertThat(producer).containsKey("max.block.ms");
    }
}
