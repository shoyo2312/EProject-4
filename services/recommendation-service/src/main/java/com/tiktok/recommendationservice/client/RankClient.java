package com.tiktok.recommendationservice.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tiktok.recommendationservice.dto.rank.CandidateFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls the LightGBM ranker in {@code services/rank-service}.
 *
 * <p>Every failure path returns an empty map rather than throwing, because a feed that returns
 * nothing when the model is down is worse than a feed ordered by the heuristic that shipped
 * before the model existed. The model improves the ordering; it is not a dependency of the
 * endpoint. It is also reachable only on the internal network, which is why this call carries
 * no JWT.
 *
 * <p>The timeout is short on purpose. This sits inside a request a person is waiting on, so a
 * ranker that has stopped answering must cost the feed a fraction of a second and then be
 * ignored, rather than hold the thread until some default read timeout measured in minutes.
 */
@Slf4j
@Component
public class RankClient {

    private final RestClient restClient;
    private final boolean enabled;

    public RankClient(@Value("${reco.rank.base-url:http://localhost:8098}") String baseUrl,
                      @Value("${reco.rank.enabled:true}") boolean enabled,
                      @Value("${reco.rank.timeout-millis:150}") long timeoutMillis) {
        this.enabled = enabled;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /** Model score per videoId, or an empty map when ranking is off, unreachable, or untrained. */
    public Map<String, Double> score(Long userId, List<CandidateFeatures> candidates) {
        if (!enabled || candidates.isEmpty()) {
            return Map.of();
        }
        try {
            RankResponse response = restClient.post()
                    .uri("/rank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RankRequest(userId, candidates))
                    .retrieve()
                    .body(RankResponse.class);
            return response == null || response.scores() == null ? Map.of() : response.scores();
        } catch (RuntimeException e) {
            log.warn("Ranker unavailable, falling back to the heuristic ordering: {}", e.getMessage());
            return Map.of();
        }
    }

    record RankRequest(@JsonProperty("user_id") Long userId, List<CandidateFeatures> candidates) {
    }

    record RankResponse(Map<String, Double> scores, @JsonProperty("model_version") String modelVersion) {
    }
}
