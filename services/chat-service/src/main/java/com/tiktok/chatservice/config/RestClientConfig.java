package com.tiktok.chatservice.config;

import com.tiktok.common.http.PooledRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.time.Duration;

/**
 * Timeouts for the downstream reads the realtime flushers make.
 *
 * <p>Without them a hung interaction-service is not an error the flusher can catch — it is a
 * blocked thread, and the scheduler's pool is small enough that one blocked call stops every
 * other realtime frame in the process. The values sit under the flush window on purpose: a call
 * that has not answered by the time the next window opens has already missed its frame, and the
 * next event on that video produces another one.
 *
 * <p>One pooled factory shared by every client: Boot's {@code ClientHttpRequestFactories.get}
 * falls back to HttpURLConnection when no other HTTP library is present, and that keeps five idle
 * connections per host — see {@link PooledRequestFactory}.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClientCustomizer downstreamTimeouts(
            @Value("${downstream.connect-timeout-millis}") long connectTimeoutMillis,
            @Value("${downstream.read-timeout-millis}") long readTimeoutMillis) {
        ClientHttpRequestFactory requestFactory = PooledRequestFactory.create(
                Duration.ofMillis(connectTimeoutMillis), Duration.ofMillis(readTimeoutMillis));
        return builder -> builder.requestFactory(requestFactory);
    }
}
