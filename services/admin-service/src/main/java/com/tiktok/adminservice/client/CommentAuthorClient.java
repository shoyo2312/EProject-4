package com.tiktok.adminservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Who wrote a comment, from interaction-service's internal prefix (tokenless, denied at the
 * gateway). Asked when a comment is reported, to refuse a report by the comment's own author.
 * Fails open — an unreachable or unknowing interaction-service answers "unknown" — because a
 * report held up by an outage would be held up for every honest reporter too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentAuthorClient {

    private final RestClient interactionServiceRestClient;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Author data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Author(Long userId) {
    }

    public Optional<Long> authorOf(Long videoId, Long commentId) {
        try {
            Envelope envelope = interactionServiceRestClient.get()
                    .uri("/api/v1/interactions/internal/videos/{videoId}/comments/{commentId}/author",
                            videoId, commentId)
                    .retrieve()
                    .body(Envelope.class);
            return envelope == null || envelope.data() == null
                    ? Optional.empty() : Optional.ofNullable(envelope.data().userId());
        } catch (RestClientException e) {
            log.warn("Could not ask interaction-service who wrote comment {}:{}: {}", videoId, commentId, e.getMessage());
            return Optional.empty();
        }
    }
}
