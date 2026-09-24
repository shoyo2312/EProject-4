package com.tiktok.interactionservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class DownstreamRestClientConfig {

    /**
     * video-service's own GET endpoints are {@code permitAll} (see its SecurityConfig), so this
     * carries no auth header — the video's owner id is public information, the same as loading
     * the video page itself. Which is why the calls go to {@code /videos/{id}/policy}: the video
     * endpoint proper filters by visibility and would answer 404 to an anonymous caller for every
     * video that is not already PUBLIC and PUBLISHED.
     */
    @Bean
    public RestClient videoServiceRestClient(@Value("${downstream.video-service-uri}") String videoServiceUri,
                                             @Value("${downstream.timeout-millis:1000}") long timeoutMillis) {
        // Without a timeout the JDK waits forever, so a downstream that accepts the connection
        // and never answers holds a request thread per call until the pool runs dry.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return RestClient.builder().baseUrl(videoServiceUri).requestFactory(requestFactory).build();
    }

    /**
     * user-service, for the mutual-follow check a FRIENDS comment audience needs. Unlike the
     * client above this one does carry an auth header — the commenter's own, forwarded per call by
     * {@code FriendshipClient}, because user-service's {@code /friendship} endpoint requires a JWT.
     */
    @Bean
    public RestClient userServiceRestClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                                            @Value("${downstream.timeout-millis:1000}") long timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return RestClient.builder().baseUrl(userServiceUri).requestFactory(requestFactory).build();
    }
}
