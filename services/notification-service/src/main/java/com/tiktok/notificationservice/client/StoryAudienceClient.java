package com.tiktok.notificationservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * One page of the accounts a new story is announced to, asked of user-service's internal
 * endpoint. user-service already leaves out blocks in either direction, closed accounts and
 * followers who muted the owner, so nothing is filtered again here.
 *
 * <p>No catch, unlike {@link BlockClient}: a swallowed failure on page three would end the fan-out
 * there and have the event recorded as done, with the rest of the audience never told.
 */
@Component
@RequiredArgsConstructor
public class StoryAudienceClient {

    private static final int PAGE_SIZE = 500;

    private final RestClient userServiceRestClient;

    /**
     * @param userIds ascending by id
     * @param next    the {@code after} that asks for the following page, null on the last one
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(List<Long> userIds, Long next) {
    }

    /**
     * @param scope FOLLOWERS for a PUBLIC story, FRIENDS for a FRIENDS one
     * @param after the previous page's {@code next}, null for the first page
     */
    public Page page(Long ownerId, String scope, Long after) {
        ApiResponse<Page> response = userServiceRestClient.get()
                .uri(uri -> {
                    uri.path("/api/v1/users/internal/story-audience")
                            .queryParam("userId", ownerId)
                            .queryParam("scope", scope);
                    if (after != null) {
                        uri.queryParam("after", after);
                    }
                    return uri.queryParam("size", PAGE_SIZE).build();
                })
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (response == null || response.data() == null) {
            throw new IllegalStateException("user-service answered the audience of userId=" + ownerId + " without a page");
        }
        return response.data();
    }
}
