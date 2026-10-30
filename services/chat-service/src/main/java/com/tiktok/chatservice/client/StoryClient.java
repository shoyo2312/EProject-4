package com.tiktok.chatservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.chatservice.dto.response.StoryCard;
import com.tiktok.chatservice.exception.StoryCheckUnavailableException;
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

/**
 * Asks story-service, which owns stories and who may watch them, what a viewer may see. No cache:
 * every rendering rechecks the viewer's current access, and the presigned URLs it returns expire.
 */
@Component
public class StoryClient {
    private final RestClient restClient;
    private static final ParameterizedTypeReference<ApiResponse<List<Viewable>>> RESPONSE =
            new ParameterizedTypeReference<>() {};

    public StoryClient(RestClient.Builder builder,
                       @Value("${downstream.story-service-uri:http://localhost:8086}") String uri,
                       InternalApiToken token) {
        restClient = builder.baseUrl(uri).defaultHeader(InternalApiToken.HEADER, token.value()).build();
    }

    /** Batches of at most 50. A story the viewer may not see comes back UNAVAILABLE, never as an error. */
    public Map<String, StoryCard> viewable(Long viewerId, Collection<String> storyIds) {
        List<String> ids = storyIds.stream().distinct().toList();
        Map<String, StoryCard> cards = new LinkedHashMap<>();
        for (int offset = 0; offset < ids.size(); offset += 50) {
            List<String> batch = ids.subList(offset, Math.min(offset + 50, ids.size()));
            ApiResponse<List<Viewable>> response;
            try {
                response = restClient.get().uri(uri -> uri.path("/api/v1/stories/internal/viewable")
                        .queryParam("viewerId", viewerId).queryParam("ids", String.join(",", batch)).build())
                        .retrieve().body(RESPONSE);
            } catch (RestClientException e) {
                throw new StoryCheckUnavailableException();
            }
            if (response == null || !response.success() || response.data() == null) {
                throw new StoryCheckUnavailableException();
            }
            batch.forEach(id -> cards.put(id, StoryCard.unavailable(id)));
            for (Viewable story : response.data()) {
                if (batch.contains(story.id())) {
                    cards.put(story.id(), new StoryCard(story.id(), "AVAILABLE", story.userId(),
                            story.mediaType(), story.mediaUrl()));
                }
            }
        }
        return cards;
    }

    /** For reading history: an outage draws UNKNOWN cards instead of failing the whole page. */
    public Map<String, StoryCard> hydrate(Long viewerId, Collection<String> ids) {
        try {
            return viewable(viewerId, ids);
        } catch (StoryCheckUnavailableException e) {
            Map<String, StoryCard> unknown = new LinkedHashMap<>();
            ids.forEach(id -> unknown.put(id, StoryCard.unknown(id)));
            return unknown;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Viewable(String id, Long userId, String mediaType, String mediaUrl) {}
}
