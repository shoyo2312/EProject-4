package com.tiktok.mediaworker.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.mediaworker.config.ModerationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls moderation-service in {@code services/moderation-service}.
 *
 * <p>The JDK's own client rather than RestClient: media-worker is not a web application — it has
 * no servlet container and no spring-web starter — and one POST does not justify pulling one in.
 *
 * <p>Every failure surfaces as {@link ModerationUnavailableException}. Nothing here decides what
 * that means for the video; that judgement belongs to the caller, which turns an exhausted set of
 * attempts into a video the admin queue picks up rather than one that is published or removed on
 * the strength of a failed call.
 *
 * <p>No JWT: this service is on the internal network only, holds no user data and does no auth,
 * exactly like rank-service.
 */
@Slf4j
@Component
public class ModerationClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final Duration timeout;

    public ModerationClient(ModerationProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.timeout = Duration.ofMillis(properties.timeoutMillis());
        this.endpoint = URI.create(properties.baseUrl().replaceAll("/+$", "") + "/moderate");
        // HTTP/1.1 explicitly. The JDK client defaults to HTTP_2, which over cleartext means an
        // h2c upgrade attempt — and uvicorn answers that as HTTP/1.1 having dropped the request
        // body, so every call came back 422 "body: Field required" with no hint that the body had
        // ever been sent. Nothing here needs HTTP/2: it is one POST to one internal service.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(this.timeout)
                .build();
    }

    /**
     * @param base64Frames JPEG frames, already sampled and downscaled
     * @throws ModerationUnavailableException when no verdict could be obtained
     */
    public ModerationResponse moderate(List<String> base64Frames) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of("frames", base64Frames));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ModerationUnavailableException(
                        "moderation-service answered %d: %s".formatted(response.statusCode(), response.body()));
            }
            return objectMapper.readValue(response.body(), ModerationResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModerationUnavailableException("Interrupted waiting for moderation-service", e);
        } catch (IOException e) {
            throw new ModerationUnavailableException("Could not reach moderation-service at " + endpoint, e);
        }
    }
}
