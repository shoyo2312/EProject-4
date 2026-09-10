package com.tiktok.chatservice.realtime;

import com.tiktok.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Reads counters from interaction-service over HTTP. Not from Cassandra — interaction-service owns
 * that keyspace, and a second reader of another service's database is what rule §6 forbids.
 */
@Component
public class InteractionCountsClient {

    private static final ParameterizedTypeReference<ApiResponse<List<CountsRow>>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public InteractionCountsClient(RestClient.Builder builder,
                                   @Value("${downstream.interaction-service-uri}") String baseUri) {
        this.restClient = builder.baseUrl(baseUri).build();
    }

    /** @return one frame per video the service answered for; empty if it answered with nothing. */
    public List<VideoFrame> fetch(List<String> videoIds) {
        if (videoIds.isEmpty()) {
            return List.of();
        }
        ApiResponse<List<CountsRow>> response = restClient.get()
                .uri(uri -> uri.path("/api/v1/interactions/videos/counts/batch")
                        .queryParam("videoIds", String.join(",", videoIds))
                        .build())
                .retrieve()
                .body(RESPONSE_TYPE);

        if (response == null || response.data() == null) {
            return List.of();
        }
        return response.data().stream()
                .map(row -> VideoFrame.counts(String.valueOf(row.videoId()), row.likeCount(),
                        row.commentCount(), row.shareCount(), row.viewCount(), row.saveCount()))
                .toList();
    }

    /**
     * A local mirror of interaction-service's InteractionCountResponse. Copied rather than shared,
     * because event-schema carries events and this is an HTTP contract; a shared DTO here would
     * tie the two services' releases together for no gain.
     */
    private record CountsRow(
            Long videoId,
            long likeCount,
            long commentCount,
            long shareCount,
            long viewCount,
            long saveCount
    ) {
    }
}
