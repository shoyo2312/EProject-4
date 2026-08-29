package com.tiktok.videoservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The one outbound call video-service makes: FRIENDS visibility needs to know whether a viewer
 * and an owner are mutual followers, and user-service is the only place that knows. Mirrors
 * interaction-service's {@code DownstreamRestClientConfig}.
 */
@Configuration
public class DownstreamRestClientConfig {

    @Bean
    public RestClient userServiceRestClient(@Value("${downstream.user-service-uri}") String userServiceUri) {
        return RestClient.builder().baseUrl(userServiceUri).build();
    }
}
