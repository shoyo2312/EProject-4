package com.tiktok.interactionservice.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.http.PooledRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Both clients carry the internal API token: what they call — video-service's {@code /policy},
 * user-service's {@code /internal/} prefix — checks it (see {@link InternalApiToken}).
 */
@Configuration
public class DownstreamRestClientConfig {

    /**
     * No user's token on this one — the video's owner id is public information, the same as
     * loading the video page itself. Which is why the calls go to {@code /videos/{id}/policy}: the
     * video endpoint proper filters by visibility and would answer 404 to an anonymous caller for
     * every video that is not already PUBLIC and PUBLISHED.
     */
    @Bean
    public RestClient videoServiceRestClient(@Value("${downstream.video-service-uri}") String videoServiceUri,
                                             @Value("${downstream.timeout-millis:1000}") long timeoutMillis,
                                             InternalApiToken internalApiToken) {
        // Without a timeout the JDK waits forever, so a downstream that accepts the connection
        // and never answers holds a request thread per call until the pool runs dry.
        Duration timeout = Duration.ofMillis(timeoutMillis);
        return RestClient.builder().baseUrl(videoServiceUri)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value())
                .requestFactory(PooledRequestFactory.create(timeout, timeout)).build();
    }

    /**
     * user-service, for the mutual-follow check a FRIENDS comment audience needs. Unlike the
     * client above this one does carry an auth header — the commenter's own, forwarded per call by
     * {@code FriendshipClient}, because user-service's {@code /friendship} endpoint requires a JWT.
     */
    @Bean
    public RestClient userServiceRestClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                                            @Value("${downstream.timeout-millis:1000}") long timeoutMillis,
                                            InternalApiToken internalApiToken) {
        Duration timeout = Duration.ofMillis(timeoutMillis);
        return RestClient.builder().baseUrl(userServiceUri)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value())
                .requestFactory(PooledRequestFactory.create(timeout, timeout)).build();
    }
}
