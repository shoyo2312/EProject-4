package com.tiktok.chatservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.chatservice.exception.BlockCheckUnavailableException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Asks user-service whether two accounts are on either side of a block. A block hides both sides
 * from each other everywhere else on the platform, and a direct message is the most personal
 * channel there is, so it has to hold here too.
 *
 * <p>Fails closed. The point of a block is that it cannot be walked around, and an outage of
 * user-service is not a reason for it to stop applying; messaging pauses instead.
 *
 * <p>The internal endpoint takes no user token — only the internal API token — because the check
 * runs for WebSocket frames too, where there is no Authorization header to forward. Timeouts come
 * from {@code RestClientConfig}.
 */
@Component
public class BlockClient {

    private static final ParameterizedTypeReference<ApiResponse<BlockStatus>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public record BlockState(boolean blockedByViewer, boolean blockedViewer, long version) {
    }

    private record StateResponse(Long userId, Boolean blockedByViewer, Boolean blockedViewer, Long version) {
    }

    private static final ParameterizedTypeReference<ApiResponse<List<StateResponse>>> STATES_TYPE =
            new ParameterizedTypeReference<>() {};

    /** Presentation only: an unavailable or malformed answer is unknown, never an invented block. */
    public Map<Long, BlockState> statesOf(Long viewerId, Collection<Long> otherIds) {
        try {
            if (otherIds.isEmpty()) {
                return Map.of();
            }
            List<Long> ids = otherIds.stream().distinct().toList();
            var response = restClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/blocks/states")
                            .queryParam("viewerId", viewerId)
                            .queryParam("otherIds", ids.stream().map(String::valueOf).collect(Collectors.joining(",")))
                            .build())
                    .retrieve().body(STATES_TYPE);
            if (response == null || !response.success() || response.data() == null) {
                return Map.of();
            }
            Map<Long, BlockState> result = new LinkedHashMap<>();
            for (StateResponse state : response.data()) {
                if (state == null || state.userId() == null || state.blockedByViewer() == null
                        || state.blockedViewer() == null || state.version() == null || state.version() < 0
                        || !ids.contains(state.userId()) || result.containsKey(state.userId())) {
                    return Map.of();
                }
                result.put(state.userId(), new BlockState(state.blockedByViewer(), state.blockedViewer(), state.version()));
            }
            return result.size() == ids.size() ? Map.copyOf(result) : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    public BlockClient(RestClient.Builder builder,
                       @Value("${downstream.user-service-uri}") String userServiceUri,
                       InternalApiToken internalApiToken) {
        this.restClient = builder.baseUrl(userServiceUri)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value()).build();
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
