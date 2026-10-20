package com.tiktok.storyservice.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.http.PooledRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * story-service's outbound calls, all to user-service: who the viewer follows among private owners,
 * whether viewer and owner are mutual followers, and whether either blocked the other. The
 * internal ones check the internal API token this client carries; the friendship call forwards the
 * viewer's own JWT besides. Mirrors video-service's DownstreamRestClientConfig.
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
}
