package com.tiktok.adminservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class DownstreamRestClientConfig {

    /**
     * moderation-service is internal-only — no gateway route and no auth — so the console cannot
     * read its settings itself. This service is already the console's backend and is already
     * behind ROLE_ADMIN, which makes it the one place that can ask on its behalf.
     */
    @Bean
    public RestClient moderationServiceRestClient(
            @Value("${downstream.moderation-service-uri:http://localhost:8099}") String moderationServiceUri,
            @Value("${downstream.timeout-millis:2000}") long timeoutMillis) {
        // A settings screen is worth a couple of seconds and no more: without a timeout a
        // classifier still loading its model would hold the request thread until the container
        // gave up on it.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return RestClient.builder().baseUrl(moderationServiceUri).requestFactory(requestFactory).build();
    }
}
