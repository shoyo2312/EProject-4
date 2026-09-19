package com.tiktok.videoservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The one outbound call video-service makes: FRIENDS visibility needs to know whether a viewer
 * and an owner are mutual followers, and user-service is the only place that knows. Mirrors
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
