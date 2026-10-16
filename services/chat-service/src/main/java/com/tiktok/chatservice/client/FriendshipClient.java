package com.tiktok.chatservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.chatservice.exception.DmSettingsUnavailableException;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Whether two accounts follow each other, which is what a {@link DmAudience#FRIENDS} recipient
 * means by "friends".
 *
 * <p>{@code /users/internal/friendship}, which takes the internal API token and no user's, not the
 * authenticated {@code /users/{id}/friendship} that video-service uses: a conversation opens from a
 * WebSocket frame as well as from a REST call, and there is no bearer token to forward on the
 * frame. That endpoint exists because of this client.
 *
 * <p>Fails closed, like {@link SettingsClient} and {@link BlockClient} — "only my friends" must
 * not become "everyone" because user-service could not be reached.
 */
@Component
public class FriendshipClient {

    private static final ParameterizedTypeReference<ApiResponse<FriendshipView>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public FriendshipClient(RestClient.Builder builder,
                            @Value("${downstream.user-service-uri}") String userServiceUri,
                            InternalApiToken internalApiToken) {
        this.restClient = builder.baseUrl(userServiceUri)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value()).build();
    }

    public boolean areFriends(Long userA, Long userB) {
        ApiResponse<FriendshipView> response;
        try {
            response = restClient.get()
                    .uri(uri -> uri.path("/api/v1/users/internal/friendship")
                            .queryParam("userA", userA)
                            .queryParam("userB", userB)
                            .build())
                    .retrieve()
                    .body(RESPONSE_TYPE);
        } catch (RestClientException e) {
            throw new DmSettingsUnavailableException(e);
        }

        if (response == null || response.data() == null) {
            throw new DmSettingsUnavailableException(null);
        }
        return response.data().friends();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FriendshipView(boolean friends) {
    }
}
