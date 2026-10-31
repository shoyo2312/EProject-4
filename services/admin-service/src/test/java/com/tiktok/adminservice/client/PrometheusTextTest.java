package com.tiktok.adminservice.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusTextTest {

    /** As Micrometer serves it, comments, other metrics and the application tag included. */
    private static final String EXPOSITION = """
            # HELP outbox_oldest_pending_age_seconds How long the oldest unacknowledged outbox row has been due
            # TYPE outbox_oldest_pending_age_seconds gauge
            outbox_oldest_pending_age_seconds{application="video-service",outbox="published"} 12.5
            outbox_oldest_pending_age_seconds{application="video-service",outbox="purge"} 0.0
            # HELP outbox_pending_events Outbox rows not yet acknowledged by the broker
            # TYPE outbox_pending_events gauge
            outbox_pending_events{application="video-service",outbox="published"} 3.0
            outbox_pending_events{application="video-service",outbox="purge"} NaN
            outbox_pending_events_other{application="video-service",outbox="published"} 99.0
            http_server_requests_seconds_count{application="video-service",outbox="x",uri="/a\\"b"} 4
            jvm_threads_live_threads{application="video-service"} 42.0
            """;

    @Test
    void readsOneMetricByLabelInTheOrderServed() {
        assertThat(PrometheusText.byLabel(EXPOSITION, "outbox_pending_events", "outbox"))
                .containsExactly(Map.entry("published", 3.0), Map.entry("purge", Double.NaN));
        assertThat(PrometheusText.byLabel(EXPOSITION, "outbox_oldest_pending_age_seconds", "outbox"))
                .containsExactly(Map.entry("published", 12.5), Map.entry("purge", 0.0));
    }

    @Test
    void aMetricWithoutTheLabelOrNotThereAtAllIsEmpty() {
        assertThat(PrometheusText.byLabel(EXPOSITION, "jvm_threads_live_threads", "outbox")).isEmpty();
        assertThat(PrometheusText.byLabel(EXPOSITION, "outbox_never_served", "outbox")).isEmpty();
        assertThat(PrometheusText.byLabel("", "outbox_pending_events", "outbox")).isEmpty();
    }

    @Test
    void aLabelValueWithAnEscapedQuoteDoesNotEndTheLabels() {
        assertThat(PrometheusText.byLabel(EXPOSITION, "http_server_requests_seconds_count", "uri"))
                .containsExactly(Map.entry("/a\\\"b", 4.0));
    }
}
