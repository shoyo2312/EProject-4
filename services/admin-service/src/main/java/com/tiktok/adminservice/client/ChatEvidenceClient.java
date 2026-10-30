package com.tiktok.adminservice.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.adminservice.exception.ChatEvidenceUnavailableException;
import com.tiktok.adminservice.exception.ReportEvidenceRejectedException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.List;

/**
 * The evidence of a direct-message report, taken by chat-service: the one service that knows who is
 * in a conversation and what each of them may still see. Admin never reads its Mongo, and never
 * trusts a client's account of what was said.
 */
@Component
@RequiredArgsConstructor
public class ChatEvidenceClient {

    private final RestClient chatServiceRestClient;

    public record Request(Long reporterId, String conversationId, List<String> messageIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Captured(String conversationId, Long reportedUserId, Instant capturedAt, List<Message> messages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String messageId, Long senderId, String type, Instant sentAt,
                          String text, String stickerId, String videoId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Captured data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Failure(String code, String message) {
    }

    public Captured capture(Long reporterId, String conversationId, List<String> messageIds) {
        Envelope envelope;
        try {
            envelope = chatServiceRestClient.post()
                    .uri("/api/v1/conversations/internal/report-evidence")
                    .body(new Request(reporterId, conversationId, messageIds))
                    .retrieve()
                    .body(Envelope.class);
        } catch (HttpClientErrorException e) {
            Failure failure = failure(e);
            throw new ReportEvidenceRejectedException(
                    failure != null && failure.code() != null ? failure.code() : "REPORT_EVIDENCE_REJECTED",
                    failure != null && failure.message() != null ? failure.message() : "The selected messages cannot be reported",
                    HttpStatus.valueOf(e.getStatusCode().value()));
        } catch (RestClientException e) {
            throw new ChatEvidenceUnavailableException(e);
        }
        if (envelope == null || envelope.data() == null) {
            throw new ChatEvidenceUnavailableException(null);
        }
        return envelope.data();
    }

    private static Failure failure(HttpClientErrorException e) {
        try {
            return e.getResponseBodyAs(Failure.class);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
