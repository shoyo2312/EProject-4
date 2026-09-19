package com.tiktok.chatservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Timeouts for the downstream reads the realtime flushers make.
 *
 * <p>Without them a hung interaction-service is not an error the flusher can catch — it is a
 * blocked thread, and the scheduler's pool is small enough that one blocked call stops every
 * other realtime frame in the process. The values sit under the flush window on purpose: a call
 * that has not answered by the time the next window opens has already missed its frame, and the
 * next event on that video produces another one.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer downstreamTimeouts(
            @Value("${downstream.connect-timeout-millis}") long connectTimeoutMillis,
            @Value("${downstream.read-timeout-millis}") long readTimeoutMillis) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .withReadTimeout(Duration.ofMillis(readTimeoutMillis));
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }
}
