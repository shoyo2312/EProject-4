package com.tiktok.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Whether either of two accounts has blocked the other, asked of user-service's internal
 * endpoint (no token; the gateway denies that prefix).
 *
 * <p>Fails closed by throwing: a notification carries the other account's words (a comment body
 * goes into the push verbatim), so an unanswered check must not deliver what a block would have
 * stopped. The consumer releases its claim and kafka-lib redelivers — until user-service answers
 * when it is unreachable, three times then the DLT for anything else.
 */
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
        ApiResponse<BlockStatus> response = userServiceRestClient.get()
                .uri(uri -> uri.path("/api/v1/users/internal/blocks")
                        .queryParam("userA", userA)
                        .queryParam("userB", userB)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return response != null && response.data() != null && response.data().blocked();
    }
}
