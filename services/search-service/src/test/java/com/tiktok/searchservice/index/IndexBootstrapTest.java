package com.tiktok.searchservice.index;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IndexBootstrapTest {

    @Test
    void hasPrefixSubfield_isTrueOnlyWhenTitleCarriesThePrefixField() {
        Map<String, Object> current = Map.of("properties", Map.of(
                "title", Map.of("type", "text", "fields", Map.of("prefix", Map.of("type", "text")))));
        Map<String, Object> legacy = Map.of("properties", Map.of("title", Map.of("type", "text")));

        assertThat(IndexBootstrap.hasPrefixSubfield(current)).isTrue();
        assertThat(IndexBootstrap.hasPrefixSubfield(legacy)).isFalse();
        assertThat(IndexBootstrap.hasPrefixSubfield(Map.of())).isFalse();
    }

    /**
     * The migration drops and recreates the live index, so it has to finish before any consumer
     * can write: a record landing in that window auto-creates {@code videos} with a dynamic
     * mapping and fails the startup. Lifecycle beans start in ascending phase order, so this is
     * what keeps it ahead of the listener containers.
     */
    @Test
    void startsBeforeTheKafkaListenerContainers() {
        int phase = new IndexBootstrap(null).getPhase();

        assertThat(phase).isLessThan(AbstractMessageListenerContainer.DEFAULT_PHASE);
    }
}
