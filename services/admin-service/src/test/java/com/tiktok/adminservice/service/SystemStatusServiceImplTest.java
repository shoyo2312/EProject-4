package com.tiktok.adminservice.service;

import com.tiktok.adminservice.client.ActuatorClient;
import com.tiktok.adminservice.config.ActuatorSourcesProperties;
import com.tiktok.adminservice.dto.response.OutboxBacklogResponse;
import com.tiktok.adminservice.dto.response.OutboxBacklogResponse.Outbox;
import com.tiktok.adminservice.dto.response.TextModerationStatusResponse;
import com.tiktok.kafka.outbox.OutboxBacklog;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemStatusServiceImplTest {

    private static final URI VIDEO = URI.create("http://video-service:8083");
    private static final URI STORY = URI.create("http://story-service:8086");

    private final ActuatorClient actuatorClient = mock(ActuatorClient.class);

    @Test
    void everyServiceIsListedWithItsOwnOutboxesAndAnUnreachableOneSaysSo() {
        when(actuatorClient.prometheus(VIDEO)).thenReturn("""
                outbox_pending_events{application="video-service",outbox="published"} 3.0
                outbox_pending_events{application="video-service",outbox="purge"} NaN
                outbox_oldest_pending_age_seconds{application="video-service",outbox="published"} 412.0
                outbox_oldest_pending_age_seconds{application="video-service",outbox="purge"} NaN
                """);
        when(actuatorClient.prometheus(STORY)).thenReturn(null);
        Map<String, URI> sources = new LinkedHashMap<>();
        sources.put("video-service", VIDEO);
        sources.put("story-service", STORY);
        OutboxBacklog own = new OutboxBacklog("outbox_events", () -> 2, () -> Instant.now().minusSeconds(30));

        List<OutboxBacklogResponse> outboxes = service(List.of(own), sources).outboxes();

        assertThat(outboxes).extracting(OutboxBacklogResponse::service)
                .containsExactly("admin-service", "story-service", "video-service");
        assertThat(outboxes.get(0).outboxes()).singleElement().satisfies(outbox -> {
            assertThat(outbox.pending()).isEqualTo(2);
            assertThat(outbox.oldestPendingAgeSeconds()).isCloseTo(30.0, within(5.0));
        });
        assertThat(outboxes.get(1).reachable()).isFalse();
        assertThat(outboxes.get(2).outboxes()).containsExactly(
                new Outbox("published", 3L, 412.0),
                new Outbox("purge", null, null));
    }

    /** Its database down: unknown, not an empty outbox. */
    @Test
    void anOwnOutboxThatCannotBeCountedIsUnknown() {
        OutboxBacklog broken = new OutboxBacklog("outbox_events", () -> {
            throw new IllegalStateException("no connection");
        }, () -> null);

        assertThat(service(List.of(broken), Map.of()).outboxes()).singleElement()
                .satisfies(response -> assertThat(response.outboxes())
                        .containsExactly(new Outbox("outbox_events", null, null)));
    }

    /** A build from before the contributor answers /actuator/info without it: unknown, not off. */
    @Test
    void eachServiceReportsItsOwnEnforcement() {
        URI interaction = URI.create("http://interaction-service:8085");
        when(actuatorClient.info(VIDEO)).thenReturn(Map.of("textModeration", Map.of(
                "enforce", false,
                "version", "rules-2026.10.1+admin-14",
                "classifier", Map.of("enabled", true, "enforce", true))));
        when(actuatorClient.info(interaction)).thenReturn(Map.of("build", Map.of("version", "1.0")));
        when(actuatorClient.info(STORY)).thenReturn(null);
        Map<String, URI> sources = new LinkedHashMap<>();
        sources.put("video-service", VIDEO);
        sources.put("interaction-service", interaction);
        sources.put("story-service", STORY);

        List<TextModerationStatusResponse> statuses = new SystemStatusServiceImpl(List.of(), actuatorClient,
                new ActuatorSourcesProperties(Duration.ofSeconds(2), Map.of(), sources)).textModeration();

        assertThat(statuses).containsExactly(
                new TextModerationStatusResponse("video-service", true, false, "rules-2026.10.1+admin-14", true, true),
                new TextModerationStatusResponse("interaction-service", true, null, null, null, null),
                new TextModerationStatusResponse("story-service", false, null, null, null, null));
    }

    private SystemStatusServiceImpl service(List<OutboxBacklog> own, Map<String, URI> outboxes) {
        return new SystemStatusServiceImpl(own, actuatorClient,
                new ActuatorSourcesProperties(Duration.ofSeconds(2), outboxes, Map.of()));
    }
}
