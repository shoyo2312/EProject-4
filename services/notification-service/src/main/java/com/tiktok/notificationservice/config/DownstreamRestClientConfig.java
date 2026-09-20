package com.tiktok.notificationservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class DownstreamRestClientConfig {

    /**
     * Same client and same endpoint as interaction-service's: {@code /videos/{id}/policy} answers
     * who owns a video without filtering by visibility, and carries no auth header because it is
     * reachable only from the internal network (it has no gateway route). The video endpoint
     * proper would answer 404 to this anonymous caller for every video that is not already public
     * and published — which is exactly the set whose owner still has to be told about a like.
     */
    @Bean
    public RestClient videoServiceRestClient(@Value("${downstream.video-service-uri}") String videoServiceUri,
                                             @Value("${downstream.timeout-millis:1000}") long timeoutMillis) {
        // Without a timeout the JDK waits forever, so a video-service that accepts the connection
        // and never answers parks a Kafka listener thread per event until the container stalls.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMillis));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMillis));
        return RestClient.builder().baseUrl(videoServiceUri).requestFactory(requestFactory).build();
    }
}
