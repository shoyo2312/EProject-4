package com.tiktok.chatservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.chatservice.dto.response.VideoCard;
import com.tiktok.chatservice.exception.VideoCheckUnavailableException;
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

/** No local cache (TTL zero): every rendering rechecks the viewer's current access. */
@Component
public class VideoClient {
    private final RestClient restClient;
    private static final ParameterizedTypeReference<ApiResponse<List<Viewable>>> RESPONSE =
            new ParameterizedTypeReference<>() {};

    public VideoClient(RestClient.Builder builder,
                       @Value("${downstream.video-service-uri:http://localhost:8083}") String uri,
                       InternalApiToken token) {
        restClient = builder.baseUrl(uri).defaultHeader(InternalApiToken.HEADER, token.value()).build();
    }

    /** Batches of at most 50, including originals of replies. Never one call per message. */
    public Map<String, VideoCard> viewable(Long viewerId, Collection<String> videoIds) {
        List<String> ids = videoIds.stream().distinct().toList();
        Map<String, VideoCard> cards = new LinkedHashMap<>();
        for (int offset = 0; offset < ids.size(); offset += 50) {
            List<String> batch = ids.subList(offset, Math.min(offset + 50, ids.size()));
            ApiResponse<List<Viewable>> response;
            try {
                response = restClient.get().uri(uri -> uri.path("/api/v1/videos/internal/viewable")
                        .queryParam("viewerId", viewerId).queryParam("ids", String.join(",", batch)).build())
                        .retrieve().body(RESPONSE);
            } catch (RestClientException e) {
                throw new VideoCheckUnavailableException();
            }
            if (response == null || !response.success() || response.data() == null) {
                throw new VideoCheckUnavailableException();
            }
            batch.forEach(id -> cards.put(id, VideoCard.unavailable(id)));
            for (Viewable video : response.data()) {
                if (batch.contains(video.id())) {
                    cards.put(video.id(), new VideoCard(video.id(), "AVAILABLE", video.userId(),
                            video.thumbnailUrl(), video.title(), video.durationSeconds()));
                }
            }
        }
        return cards;
    }

    public Map<String, VideoCard> hydrate(Long viewerId, Collection<String> ids) {
        try {
            return viewable(viewerId, ids);
        } catch (VideoCheckUnavailableException e) {
            Map<String, VideoCard> unknown = new LinkedHashMap<>();
            ids.forEach(id -> unknown.put(id, VideoCard.unknown(id)));
            return unknown;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Viewable(String id, Long userId, String thumbnailUrl, String title, Integer durationSeconds) {}
}
