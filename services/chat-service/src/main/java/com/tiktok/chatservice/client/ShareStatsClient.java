package com.tiktok.chatservice.client;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.http.PooledRequestFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Slf4j
@Component
public class ShareStatsClient {
    private final RestClient restClient;

    public ShareStatsClient(RestClient.Builder builder,
                            @Value("${downstream.interaction-service-uri:http://localhost:8085}") String uri,
                            InternalApiToken token) {
        restClient = builder.baseUrl(uri).defaultHeader(InternalApiToken.HEADER, token.value())
                .requestFactory(PooledRequestFactory.create(Duration.ofMillis(300), Duration.ofMillis(500))).build();
    }

    /** Best effort, once after delivery. A stats outage must never turn a delivered message into a failed send. */
    public void count(String videoId, Long userId, String messageId) {
        try {
            restClient.post().uri("/api/v1/interactions/internal/videos/{id}/shares", videoId)
                    .body(new ShareRequest(userId, messageId)).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Could not count chat share {} for video {}", messageId, videoId, e);
        }
    }

    private record ShareRequest(Long userId, String dedupeKey) {}
}
