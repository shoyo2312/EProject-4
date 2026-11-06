package com.tiktok.notificationservice.client;

import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.Map;

/**
 * Who a comment's "@handle"s name, asked of user-service's internal endpoint. Keyed by the
 * lowercased handle; unknown and closed accounts are absent.
 *
 * <p>Throws when unanswered, as {@link BlockClient} does: "could not ask" is not "tagged nobody",
 * and the delivery is retried rather than the mentions dropped.
 */
@Component
@RequiredArgsConstructor
public class UserHandleClient {

    private final RestClient userServiceRestClient;

    public Map<String, Long> idsByUsername(Collection<String> usernames) {
        ApiResponse<Map<String, Long>> response = userServiceRestClient.get()
                .uri(uri -> uri.path("/api/v1/users/internal/ids-by-username")
                        .queryParam("usernames", usernames.toArray())
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        return response == null || response.data() == null ? Map.of() : response.data();
    }
}
