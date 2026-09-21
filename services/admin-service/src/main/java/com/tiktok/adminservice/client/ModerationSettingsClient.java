package com.tiktok.adminservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * What automatic moderation is currently deciding with.
 *
 * <p>Asked for rather than configured here: the thresholds belong to the model and live in
 * moderation-service's own environment, and a second copy in this service's config would be the
 * number the console showed while the classifier used another one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModerationSettingsClient {

    private final RestClient moderationServiceRestClient;

    /**
     * Passed through as a map rather than mapped onto a record: this service neither reads nor
     * decides anything here, and every field added on the moderation side would otherwise need a
     * matching field here and in the console before it could be shown at all.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModerationSettings(Map<String, Object> values, boolean reachable) {

        static final List<String> EXPECTED = List.of("reviewAt", "rejectAt", "minRejectFrames");
    }

    public ModerationSettings fetch() {
        try {
            Map<String, Object> values = moderationServiceRestClient.get()
                    .uri("/config")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            if (values == null || !values.keySet().containsAll(ModerationSettings.EXPECTED)) {
                log.warn("moderation-service answered /config without the thresholds: {}", values);
                return new ModerationSettings(Map.of(), false);
            }
            return new ModerationSettings(values, true);
        } catch (RestClientException e) {
            // Not an error for the caller: a settings screen that says "could not reach
            // moderation-service" is more use than a 500 that says nothing about which part of
            // the page failed, and nothing else on it depends on this.
            log.warn("Could not read moderation-service settings: {}", e.getMessage());
            return new ModerationSettings(Map.of(), false);
        }
    }
}
