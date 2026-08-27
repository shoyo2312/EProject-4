package com.tiktok.interactionservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class DownstreamRestClientConfig {

    /**
     * video-service's own GET endpoints are {@code permitAll} (see its SecurityConfig), so this
     * carries no auth header — the video's owner id is public information, the same as loading
     * the video page itself.
     */
    @Bean
    public RestClient videoServiceRestClient(@Value("${downstream.video-service-uri}") String videoServiceUri) {
        return RestClient.builder().baseUrl(videoServiceUri).build();
    }
}
