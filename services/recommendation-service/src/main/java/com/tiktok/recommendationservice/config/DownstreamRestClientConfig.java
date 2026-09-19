package com.tiktok.recommendationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class DownstreamRestClientConfig {

    /**
     * Called inside the feed request a person is waiting on, so the timeout is short for the same
     * reason as the ranker's: a user-service that stops answering must cost a fraction of a second
     * and then be ignored, not hold the thread for the JDK's default of forever.
     */
    @Bean
    public RestClient userServiceRestClient(@Value("${downstream.user-service-uri}") String userServiceUri,
                                            @Value("${downstream.timeout-millis:300}") long timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return RestClient.builder().baseUrl(userServiceUri).requestFactory(requestFactory).build();
    }
}
