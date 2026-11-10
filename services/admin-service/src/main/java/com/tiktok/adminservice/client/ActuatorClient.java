package com.tiktok.adminservice.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Map;

/**
 * Another service's actuator. A service that does not answer is null, not an exception: a status
 * screen that says which service it could not reach is more use than one that fails whole.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuatorClient {

    private final RestClient actuatorRestClient;

    /** The text exposition, which is what a Prometheus scrape reads. */
    public String prometheus(URI service) {
        try {
            return actuatorRestClient.get()
                    .uri(service.resolve("/actuator/prometheus"))
                    .accept(MediaType.TEXT_PLAIN)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.warn("Could not read {}/actuator/prometheus: {}", service, e.getMessage());
            return null;
        }
    }

    public Map<String, Object> info(URI service) {
        try {
            return actuatorRestClient.get()
                    .uri(service.resolve("/actuator/info"))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException e) {
            log.warn("Could not read {}/actuator/info: {}", service, e.getMessage());
            return null;
        }
    }
}
