package com.tiktok.interactionservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Whether either of two accounts has blocked the other — asked before a reply, about the author of
 * the comment being replied to. The video owner needs no such check: video-service already hides a
 * blocked owner's videos, so the comment box is never reached. A copy of video-service's client of
 * the same name, against user-service's internal endpoint (the client sends the internal token).
 *
 * <p>Fails open, like video-service's: this is on every reply, and a user-service outage must not
 * stop all of them.
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
            log.warn("Could not check for a block between {} and {}; treating it as none", userA, userB, e);
            return false;
        }
    }
}
