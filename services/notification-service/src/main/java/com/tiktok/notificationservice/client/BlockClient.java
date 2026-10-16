package com.tiktok.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Whether either of two accounts has blocked the other, asked of user-service's internal
 * endpoint (no token; the gateway denies that prefix).
 *
 * <p>Fails open: a user-service outage delivers the notification rather than dropping it, the
 * same trade {@link VideoOwnerClient} makes against a wedged listener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockClient {

    private final RestClient userServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BlockStatus(boolean blocked) {
    }

    public boolean isBlockedBetween(Long userA, Long userB) {
        if (userA == null || userB == null || userA.equals(userB)) {
            return false;
        }
        try {
            ApiResponse<BlockStatus> response = userServiceRestClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/blocks")
                            .queryParam("userA", userA)
                            .queryParam("userB", userB)
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return response != null && response.data() != null && response.data().blocked();
        } catch (RestClientException e) {
            log.warn("Could not check for a block between {} and {}; notifying anyway", userA, userB, e);
            return false;
        }
    }
}
