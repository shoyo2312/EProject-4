package com.tiktok.searchservice.client;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.http.PooledRequestFactory;
import com.tiktok.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.List;

/**
 * Which accounts a search term names, asked of user-service's internal endpoint (no user token,
 * the internal API token instead; the gateway denies that prefix). The video index holds only the
 * author's id, and usernames are owned by user-service — asking at query time rather than copying
 * them in means no backfill for accounts registered before this existed, and a display-name change
 * is seen at once.
 *
 * <p>Fails open: a user-service outage costs the author match, not the search.
 */
@Slf4j
@Component
public class UserDirectoryClient {

    private final RestClient restClient;

    public UserDirectoryClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                               @Value("${downstream.timeout-millis:1000}") long timeoutMillis,
                               InternalApiToken internalApiToken) {
        // Without a timeout the JDK waits forever, and every search would wait with it.
        Duration timeout = Duration.ofMillis(timeoutMillis);
        this.restClient = RestClient.builder().baseUrl(userServiceUri)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value())
                .requestFactory(PooledRequestFactory.create(timeout, timeout)).build();
    }

    /** Ids of the accounts whose handle or display name contains {@code query}, best first. */
    public List<Long> findUserIds(String query) {
        try {
            ApiResponse<List<Long>> response = restClient.get()
                    // A variable, not a literal: only expanded values are fully encoded, and a
                    // literal "a&b" or "c++" would reach user-service as something else.
                    .uri(uri -> uri.path("/api/v1/users/internal/search-ids").queryParam("q", "{q}").build(query))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (RestClientException e) {
            log.warn("Could not look up authors matching '{}'; searching without them", query, e);
            return List.of();
        }
    }
}
