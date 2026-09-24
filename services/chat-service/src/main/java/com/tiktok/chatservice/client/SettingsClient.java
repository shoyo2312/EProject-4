package com.tiktok.chatservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.chatservice.exception.DmSettingsUnavailableException;
import com.tiktok.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Asks user-service who a given account accepts direct messages from. Modelled on
 * {@link BlockClient}, and for the same reasons: no token, because the check runs for WebSocket
 * frames too, where there is no Authorization header to forward; timeouts from
 * {@code RestClientConfig}; and it fails closed.
 *
 * <p>The internal endpoint answers every id it is asked for, defaults included, so there is no
 * "this account has no settings" case to handle — an empty answer means something went wrong.
 */
@Slf4j
@Component
public class SettingsClient {

    private static final ParameterizedTypeReference<ApiResponse<List<SettingsPolicy>>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public SettingsClient(RestClient.Builder builder,
                          @Value("${downstream.user-service-uri}") String userServiceUri) {
        this.restClient = builder.baseUrl(userServiceUri).build();
    }

    /**
     * The audience {@code userId} has set, never null: anything that stops this from being
     * answered is a {@link DmSettingsUnavailableException} rather than a permissive default.
     */
    public DmAudience dmAudience(Long userId) {
        ApiResponse<List<SettingsPolicy>> response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/settings")
                            .queryParam("ids", userId)
                            .build())
                    .retrieve()
                    .body(RESPONSE_TYPE);
        } catch (RestClientException e) {
            throw new DmSettingsUnavailableException(e);
        }

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new DmSettingsUnavailableException(null);
        }

        String audience = response.data().get(0).dmAudience();
        try {
            return DmAudience.valueOf(audience);
        } catch (IllegalArgumentException | NullPointerException e) {
            // A user-service deployed ahead of this one. Refusing to guess is the whole point of
            // the exception — see its javadoc for why this is not treated as EVERYONE.
            log.warn("Unknown DM audience '{}' for user {}; refusing to guess", audience, userId);
            throw new DmSettingsUnavailableException(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SettingsPolicy(Long userId, String dmAudience) {
    }
}
