package com.tiktok.storyservice.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.http.PooledRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * story-service's outbound calls, to internal endpoints that check the internal API token these
 * clients carry: user-service, for how the viewer relates to a page of owners (blocked, private
 * account, follow, mutual follow), in one call per 100; video-service, for a story's song. Mirrors video-service's DownstreamRestClientConfig.
 */
@Configuration
public class DownstreamRestClientConfig {

    @Bean
    public RestClient userServiceRestClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                                            @Value("${downstream.timeout-millis:1000}") long timeoutMillis,
                                            InternalApiToken internalApiToken) {
        // Without a timeout the JDK waits forever, so a user-service that accepts the connection
        // and never answers holds a request thread per call until the pool runs dry.
        Duration timeout = Duration.ofMillis(timeoutMillis);
        return RestClient.builder().baseUrl(userServiceUri)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value())
                .requestFactory(PooledRequestFactory.create(timeout, timeout)).build();
    }

    /**
     * video-service's music lookup, for a story's song. Its read timeout is generous: video-service
     * itself asks the catalogue, which it retries when it answers empty.
     */
    @Bean
    public RestClient videoServiceRestClient(
            @Value("${downstream.video-service-uri:http://localhost:8083}") String videoServiceUri,
            @Value("${downstream.video-service-timeout-millis:15000}") long timeoutMillis,
            InternalApiToken internalApiToken) {
        return RestClient.builder().baseUrl(videoServiceUri)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value())
                .requestFactory(PooledRequestFactory.create(Duration.ofSeconds(2), Duration.ofMillis(timeoutMillis)))
                .build();
    }
}
