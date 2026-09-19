package com.tiktok.chatservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.chatservice.exception.BlockCheckUnavailableException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks user-service whether two accounts are on either side of a block. A block hides both sides
 * from each other everywhere else on the platform, and a direct message is the most personal
 * channel there is, so it has to hold here too.
 *
 * <p>Fails closed. The point of a block is that it cannot be walked around, and an outage of
 * user-service is not a reason for it to stop applying; messaging pauses instead.
 *
 * <p>The internal endpoint takes no token because the check runs for WebSocket frames too, where
 * there is no Authorization header to forward. Timeouts come from {@code RestClientConfig}.
 */
@Component
public class BlockClient {

    private static final ParameterizedTypeReference<ApiResponse<BlockStatus>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public BlockClient(RestClient.Builder builder,
                       @Value("${downstream.user-service-uri}") String userServiceUri) {
        this.restClient = builder.baseUrl(userServiceUri).build();
    }

    public void requireNotBlocked(Long userA, Long userB) {
        ApiResponse<BlockStatus> response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/blocks")
                            .queryParam("userA", userA)
                            .queryParam("userB", userB)
                            .build())
                    .retrieve()
                    .body(RESPONSE_TYPE);
        } catch (RestClientException e) {
            throw new BlockCheckUnavailableException(e);
        }
        if (response == null || response.data() == null) {
            throw new BlockCheckUnavailableException(null);
        }
        if (response.data().blocked()) {
            throw new MessagingBlockedException();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BlockStatus(boolean blocked) {
    }
}
