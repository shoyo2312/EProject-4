package com.tiktok.adminservice.service;

import com.tiktok.adminservice.client.ActuatorClient;
import com.tiktok.adminservice.client.PrometheusText;
import com.tiktok.adminservice.config.ActuatorSourcesProperties;
import com.tiktok.adminservice.dto.response.OutboxBacklogResponse;
import com.tiktok.adminservice.dto.response.OutboxBacklogResponse.Outbox;
import com.tiktok.adminservice.dto.response.TextModerationStatusResponse;
import com.tiktok.kafka.outbox.OutboxBacklog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

@Slf4j
@Service
@RequiredArgsConstructor
public class SystemStatusServiceImpl implements SystemStatusService {

    static final String SERVICE = "admin-service";
    static final String PENDING = "outbox_pending_events";
    static final String OLDEST_AGE = "outbox_oldest_pending_age_seconds";

    private final List<OutboxBacklog> ownOutboxes;
    private final ActuatorClient actuatorClient;
    private final ActuatorSourcesProperties sources;

    @Override
    public List<OutboxBacklogResponse> outboxes() {
        List<OutboxBacklogResponse> all = new ArrayList<>(eachService(sources.outboxes(), this::scrapeOutboxes));
        all.add(new OutboxBacklogResponse(SERVICE, true, ownOutboxes.stream().map(SystemStatusServiceImpl::read).toList()));
        all.sort(Comparator.comparing(OutboxBacklogResponse::service));
        return all;
    }

    @Override
    public List<TextModerationStatusResponse> textModeration() {
        return eachService(sources.textModeration(), this::readTextModeration);
    }

    private TextModerationStatusResponse readTextModeration(String service, URI uri) {
        Map<String, Object> info = actuatorClient.info(uri);
        if (info == null) {
            return new TextModerationStatusResponse(service, false, null, null, null, null);
        }
        Map<?, ?> text = info.get("textModeration") instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> classifier = text.get("classifier") instanceof Map<?, ?> map ? map : Map.of();
        return new TextModerationStatusResponse(service, true,
                bool(text.get("enforce")),
                text.get("version") instanceof String version ? version : null,
                bool(classifier.get("enabled")),
                bool(classifier.get("enforce")));
    }

    private OutboxBacklogResponse scrapeOutboxes(String service, URI uri) {
        String exposition = actuatorClient.prometheus(uri);
        if (exposition == null) {
            return new OutboxBacklogResponse(service, false, List.of());
        }
        Map<String, Double> pending = PrometheusText.byLabel(exposition, PENDING, "outbox");
        Map<String, Double> ages = PrometheusText.byLabel(exposition, OLDEST_AGE, "outbox");
        return new OutboxBacklogResponse(service, true, pending.entrySet().stream()
                .map(entry -> new Outbox(entry.getKey(), count(entry.getValue()), known(ages.get(entry.getKey()))))
                .toList());
    }

    /** This service's own, straight from its beans: the same numbers its scrape would serve. */
    private static Outbox read(OutboxBacklog backlog) {
        try {
            return new Outbox(backlog.outbox(), backlog.pending().getAsLong(), backlog.oldestPendingAgeSeconds());
        } catch (RuntimeException e) {
            log.warn("Could not read outbox {}: {}", backlog.outbox(), e.getMessage());
            return new Outbox(backlog.outbox(), null, null);
        }
    }

    /** All at once: a screen otherwise waits out every unreachable service's timeout in turn. */
    private static <T> List<T> eachService(Map<String, URI> services, BiFunction<String, URI, T> read) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> answers = services.entrySet().stream()
                    .map(entry -> executor.submit(() -> read.apply(entry.getKey(), entry.getValue())))
                    .toList();
            List<T> results = new ArrayList<>(answers.size());
            for (Future<T> answer : answers) {
                results.add(answer.get());
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted reading the services' actuator", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Reading a service's actuator failed", e.getCause());
        }
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean flag ? flag : null;
    }

    private static Long count(Double value) {
        Double known = known(value);
        return known == null ? null : Math.round(known);
    }

    /** NaN is a gauge that could not be read, which the console shows as unknown rather than 0. */
    private static Double known(Double value) {
        return value == null || value.isNaN() ? null : value;
    }
}
