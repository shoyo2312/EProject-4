package com.tiktok.userservice.config;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.http.PooledRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The services a "Download your data" archive reads from. All three calls go to the callee's
 * internal prefix with the internal token: the archive is built by a background job, long after
 * the request that asked for it, so there is no user token to forward.
 */
@Configuration
public class DownstreamRestClientConfig {

    @Bean
    public RestClient videoServiceRestClient(@Value("${downstream.video-service-uri}") String uri,
                                             @Value("${downstream.timeout-millis}") long timeoutMillis,
                                             InternalApiToken internalApiToken) {
        return client(uri, timeoutMillis, internalApiToken);
    }

    @Bean
    public RestClient interactionServiceRestClient(@Value("${downstream.interaction-service-uri}") String uri,
                                                   @Value("${downstream.timeout-millis}") long timeoutMillis,
                                                   InternalApiToken internalApiToken) {
        return client(uri, timeoutMillis, internalApiToken);
    }

    @Bean
    public RestClient chatServiceRestClient(@Value("${downstream.chat-service-uri}") String uri,
                                            @Value("${downstream.timeout-millis}") long timeoutMillis,
                                            InternalApiToken internalApiToken) {
        return client(uri, timeoutMillis, internalApiToken);
    }

    private static RestClient client(String uri, long timeoutMillis, InternalApiToken internalApiToken) {
        // Without a timeout a downstream that accepts the connection and never answers holds the
        // export worker's thread for good, and with it every export queued behind.
        Duration timeout = Duration.ofMillis(timeoutMillis);
        return RestClient.builder().baseUrl(uri)
                .defaultHeader(InternalApiToken.HEADER, internalApiToken.value())
                .requestFactory(PooledRequestFactory.create(timeout, timeout))
                .build();
    }
}
