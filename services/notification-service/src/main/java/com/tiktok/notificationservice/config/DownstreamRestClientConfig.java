package com.tiktok.notificationservice.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.http.PooledRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class DownstreamRestClientConfig {

    /**
     * Same client and same endpoint as interaction-service's: {@code /videos/{id}/policy} answers
     * who owns a video without filtering by visibility, so it is for internal callers only — no
     * gateway route, and video-service checks the internal API token this carries instead of a
     * user's. The video endpoint proper would answer 404 to a caller with no user token for every
     * video that is not already public and published — which is exactly the set whose owner still
     * has to be told about a like.
     */
    @Bean
    public RestClient videoServiceRestClient(@Value("${downstream.video-service-uri}") String videoServiceUri,
                                             @Value("${downstream.timeout-millis:1000}") long timeoutMillis,
                                             InternalApiToken internalApiToken) {
        // Without a timeout the JDK waits forever, so a video-service that accepts the connection
        // and never answers parks a Kafka listener thread per event until the container stalls.
        return client(videoServiceUri, timeoutMillis, internalApiToken);
    }

    /**
     * For {@code CommentAuthorClient}: who wrote a comment an admin warned over, on
     * interaction-service's internal prefix — same reasoning as above.
     */
    @Bean
    public RestClient interactionServiceRestClient(
            @Value("${downstream.interaction-service-uri}") String interactionServiceUri,
            @Value("${downstream.timeout-millis:1000}") long timeoutMillis,
            InternalApiToken internalApiToken) {
        return client(interactionServiceUri, timeoutMillis, internalApiToken);
    }

    /** For {@code BlockClient}: user-service's internal block check, same reasoning as above. */
    @Bean
    public RestClient userServiceRestClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                                            @Value("${downstream.timeout-millis:1000}") long timeoutMillis,
                                            InternalApiToken internalApiToken) {
        return client(userServiceUri, timeoutMillis, internalApiToken);
    }

    /**
     * For {@code StoryAnnouncementClient}: story-service's internal check that a story is still
     * live, on a prefix the gateway denies and story-service guards with the internal API token —
     * same reasoning as above.
     */
    @Bean
    public RestClient storyServiceRestClient(@Value("${downstream.story-service-uri}") String storyServiceUri,
                                             @Value("${downstream.timeout-millis:1000}") long timeoutMillis,
                                             InternalApiToken internalApiToken) {
        return client(storyServiceUri, timeoutMillis, internalApiToken);
    }

    private static RestClient client(String baseUrl, long timeoutMillis, InternalApiToken internalApiToken) {
        Duration timeout = Duration.ofMillis(timeoutMillis);
        return RestClient.builder().baseUrl(baseUrl)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value())
                .requestFactory(PooledRequestFactory.create(timeout, timeout)).build();
    }
}
