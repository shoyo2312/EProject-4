package com.tiktok.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class DownstreamWebClientConfig {

    @Bean
    public WebClient authServiceWebClient(@Value("${downstream.auth-service-uri}") String authServiceUri) {
        return WebClient.builder().baseUrl(authServiceUri).build();
    }

    @Bean
    public WebClient userServiceWebClient(@Value("${downstream.user-service-uri}") String userServiceUri) {
        return WebClient.builder().baseUrl(userServiceUri).build();
    }
}
