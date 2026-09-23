package com.tiktok.videoservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * video-service's outbound calls, both to user-service: FRIENDS visibility needs to know whether a
 * viewer and an owner are mutual followers, and a single-video read whether either has blocked the
 * other. user-service is the only place that knows either. Mirrors
 * interaction-service's {@code DownstreamRestClientConfig}.
 */
@Configuration
public class DownstreamRestClientConfig {

    @Bean
    public RestClient userServiceRestClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                                            @Value("${downstream.timeout-millis:1000}") long timeoutMillis) {
        // Without a timeout the JDK waits forever, so a downstream that accepts the connection
        // and never answers holds a request thread per call until the pool runs dry.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return RestClient.builder().baseUrl(userServiceUri).requestFactory(requestFactory).build();
    }
}
